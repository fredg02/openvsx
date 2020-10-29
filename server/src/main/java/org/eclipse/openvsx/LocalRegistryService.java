/********************************************************************************
 * Copyright (c) 2019 TypeFox and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.openvsx;

import static org.eclipse.openvsx.util.UrlUtil.createApiUrl;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import javax.persistence.EntityManager;
import javax.transaction.Transactional;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.apache.jena.ext.com.google.common.collect.Maps;
import org.eclipse.openvsx.eclipse.EclipseService;
import org.eclipse.openvsx.entities.Extension;
import org.eclipse.openvsx.entities.ExtensionReview;
import org.eclipse.openvsx.entities.ExtensionVersion;
import org.eclipse.openvsx.entities.FileResource;
import org.eclipse.openvsx.entities.Namespace;
import org.eclipse.openvsx.entities.NamespaceMembership;
import org.eclipse.openvsx.entities.PersonalAccessToken;
import org.eclipse.openvsx.entities.UserData;
import org.eclipse.openvsx.json.ExtensionJson;
import org.eclipse.openvsx.json.NamespaceJson;
import org.eclipse.openvsx.json.QueryParamJson;
import org.eclipse.openvsx.json.QueryResultJson;
import org.eclipse.openvsx.json.ResultJson;
import org.eclipse.openvsx.json.ReviewJson;
import org.eclipse.openvsx.json.ReviewListJson;
import org.eclipse.openvsx.json.SearchEntryJson;
import org.eclipse.openvsx.json.SearchResultJson;
import org.eclipse.openvsx.repositories.RepositoryService;
import org.eclipse.openvsx.search.ExtensionSearch;
import org.eclipse.openvsx.search.SearchService;
import org.eclipse.openvsx.storage.GoogleCloudStorageService;
import org.eclipse.openvsx.storage.StorageUtilService;
import org.eclipse.openvsx.util.CollectionUtil;
import org.eclipse.openvsx.util.ErrorResultException;
import org.eclipse.openvsx.util.NotFoundException;
import org.eclipse.openvsx.util.SemanticVersion;
import org.eclipse.openvsx.util.TimeUtil;
import org.eclipse.openvsx.util.UrlUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class LocalRegistryService implements IExtensionRegistry {

    @Autowired
    EntityManager entityManager;

    @Autowired
    RepositoryService repositories;

    @Autowired
    UserService users;

    @Autowired
    SearchService search;

    @Autowired
    ExtensionValidator validator;

    @Autowired
    StorageUtilService storageUtil;

    @Autowired
    GoogleCloudStorageService googleStorage;

    @Autowired
    EclipseService eclipse;

    @Override
    public NamespaceJson getNamespace(String namespaceName) {
        var namespace = repositories.findNamespace(namespaceName);
        if (namespace == null)
            throw new NotFoundException();
        var json = new NamespaceJson();
        json.name = namespace.getName();
        json.extensions = new LinkedHashMap<>();
        var serverUrl = UrlUtil.getBaseUrl();
        for (var ext : repositories.findExtensions(namespace)) {
            String url = createApiUrl(serverUrl, "api", namespace.getName(), ext.getName());
            json.extensions.put(ext.getName(), url);
        }
        json.access = getAccessString(namespace);
        return json;
    }

    private String getAccessString(Namespace namespace) {
        var ownerships = repositories.countMemberships(namespace, NamespaceMembership.ROLE_OWNER);
        return ownerships == 0 ? NamespaceJson.PUBLIC_ACCESS : NamespaceJson.RESTRICTED_ACCESS;
    }

    @Override
    public ExtensionJson getExtension(String namespace, String extensionName) {
        var extension = repositories.findExtension(extensionName, namespace);
        if (extension == null)
            throw new NotFoundException();
        return toExtensionVersionJson(extension.getLatest());
    }

    @Override
    public ExtensionJson getExtension(String namespace, String extensionName, String version) {
        var extVersion = findVersion(namespace, extensionName, version);
        if (extVersion == null)
            throw new NotFoundException();
        return toExtensionVersionJson(extVersion);
    }

    private ExtensionVersion findVersion(String namespace, String extensionName, String version) {
        if ("latest".equals(version)) {
            var extension = repositories.findExtension(extensionName, namespace);
            if (extension == null)
                return null;
            return extension.getLatest();
        } else if ("preview".equals(version)) {
            var extension = repositories.findExtension(extensionName, namespace);
            if (extension == null)
                return null;
            return extension.getPreview();
        } else {
            return repositories.findVersion(version, extensionName, namespace);
        }
    }

    @Override
    public ResponseEntity<byte[]> getFile(String namespace, String extensionName, String version, String fileName) {
        var extVersion = findVersion(namespace, extensionName, version);
        if (extVersion == null)
            throw new NotFoundException();
        var resource = repositories.findFileByName(extVersion, fileName);
        if (resource == null)
            throw new NotFoundException();
        if (resource.getType().equals(FileResource.DOWNLOAD))
            storageUtil.increaseDownloadCount(extVersion);
        if (resource.getStorageType().equals(FileResource.STORAGE_DB)) {
            var headers = storageUtil.getFileResponseHeaders(fileName);
            return new ResponseEntity<>(resource.getContent(), headers, HttpStatus.OK);
        } else {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(storageUtil.getLocation(resource))
                    .build();
        }
    }

    @Override
    public ReviewListJson getReviews(String namespace, String extensionName) {
        var extension = repositories.findExtension(extensionName, namespace);
        if (extension == null)
            throw new NotFoundException();
        var list = new ReviewListJson();
        var serverUrl = UrlUtil.getBaseUrl();
        list.postUrl = createApiUrl(serverUrl, "api", extension.getNamespace().getName(), extension.getName(), "review");
        list.deleteUrl = createApiUrl(serverUrl, "api", extension.getNamespace().getName(), extension.getName(), "review", "delete");
        list.reviews = repositories.findActiveReviews(extension)
                .map(extReview -> extReview.toReviewJson())
                .toList();
        return list;
    }

    @Override
    public SearchResultJson search(SearchService.Options options) {
        var json = new SearchResultJson();
        var size = options.requestedSize;
        if (size <= 0 || !search.isEnabled()) {
            json.extensions = Collections.emptyList();
            return json;
        }

        var offset = options.requestedOffset;
        var pageRequest = PageRequest.of(offset / size, size);
        var searchResult = search.search(options, pageRequest);
        json.extensions = toSearchEntries(searchResult, size, offset % size, options);
        json.offset = offset;
        json.totalSize = (int) searchResult.getTotalElements();
        if (json.extensions.size() < size && searchResult.hasNext()) {
            // This is necessary when offset % size > 0
            var remainder = search.search(options, pageRequest.next());
            json.extensions.addAll(toSearchEntries(remainder, size - json.extensions.size(), 0, options));
        }
        return json;
    }

    private List<SearchEntryJson> toSearchEntries(Page<ExtensionSearch> page, int size, int offset, SearchService.Options options) {
        var serverUrl = UrlUtil.getBaseUrl();
        if (offset > 0 || size < page.getNumberOfElements())
            return CollectionUtil.map(
                    Iterables.limit(Iterables.skip(page.getContent(), offset), size),
                    es -> toSearchEntry(es, serverUrl, options));
        else
            return CollectionUtil.map(page.getContent(), es -> toSearchEntry(es, serverUrl, options));
    }

    @Override
    public QueryResultJson query(QueryParamJson param) {
        if (!Strings.isNullOrEmpty(param.extensionId)) {
            var split = param.extensionId.split("\\.");
            if (split.length != 2 || split[0].isEmpty() || split[1].isEmpty())
                throw new ErrorResultException("The 'extensionId' parameter must have the format 'namespace.extension'.");
            if (!Strings.isNullOrEmpty(param.namespaceName) && !param.namespaceName.equals(split[0]))
                throw new ErrorResultException("Conflicting parameters 'extensionId' and 'namespaceName'");
            if (!Strings.isNullOrEmpty(param.extensionName) && !param.extensionName.equals(split[1]))
                throw new ErrorResultException("Conflicting parameters 'extensionId' and 'extensionName'");
            param.namespaceName = split[0];
            param.extensionName = split[1];
        }
        var result = new QueryResultJson();
        result.extensions = new ArrayList<>();
        // Add extension by UUID (public_id)
        if (!Strings.isNullOrEmpty(param.extensionUuid)) {
            var extension = repositories.findExtensionByPublicId(param.extensionUuid);
            addToResult(extension, result, param);
        }
        // Add extensions by namespace UUID (public_id)
        if (!Strings.isNullOrEmpty(param.namespaceUuid)) {
            var namespace = repositories.findNamespaceByPublicId(param.namespaceUuid);
            addToResult(namespace, result, param);
        }
        // Add a specific version of an extension
        if (!Strings.isNullOrEmpty(param.namespaceName) && !Strings.isNullOrEmpty(param.extensionName)
                && !Strings.isNullOrEmpty(param.extensionVersion) && !param.includeAllVersions) {
            var extVersion = repositories.findVersion(param.extensionVersion, param.extensionName, param.namespaceName);
            addToResult(extVersion, result, param);
        // Add extension by namespace and name
        } else if (!Strings.isNullOrEmpty(param.namespaceName) && !Strings.isNullOrEmpty(param.extensionName)) {
            var extension = repositories.findExtension(param.extensionName, param.namespaceName);
            addToResult(extension, result, param);
        // Add extensions by namespace
        } else if (!Strings.isNullOrEmpty(param.namespaceName)) {
            var namespace = repositories.findNamespace(param.namespaceName);
            addToResult(namespace, result, param);
        // Add extensions by name
        } else if (!Strings.isNullOrEmpty(param.extensionName)) {
            var extensions = repositories.findExtensions(param.extensionName);
            for (var extension : extensions) {
                addToResult(extension, result, param);
            }
        }
        return result;
    }

    private void addToResult(Namespace namespace, QueryResultJson result, QueryParamJson param) {
        if (namespace == null)
            return;
        for (var extension : repositories.findExtensions(namespace)) {
            addToResult(extension, result, param);
        }
    }

    private void addToResult(Extension extension, QueryResultJson result, QueryParamJson param) {
        if (extension == null)
            return;
        if (param.includeAllVersions) {
            var allVersions = Lists.newArrayList(repositories.findVersions(extension));
            Collections.sort(allVersions, ExtensionVersion.SORT_COMPARATOR);
            for (var extVersion : allVersions) {
                addToResult(extVersion, result, param);
            }
        } else {
            addToResult(extension.getLatest(), result, param);
        }
    }

    private void addToResult(ExtensionVersion extVersion, QueryResultJson result, QueryParamJson param) {
        if (extVersion == null)
            return;
        if (mismatch(extVersion.getVersion(), param.extensionVersion))
            return;
        var extension = extVersion.getExtension();
        if (mismatch(extension.getName(), param.extensionName))
            return;
        var namespace = extension.getNamespace();
        if (mismatch(namespace.getName(), param.namespaceName))
            return;
        if (mismatch(extension.getPublicId(), param.extensionUuid) || mismatch(namespace.getPublicId(), param.namespaceUuid))
            return;
        if (result.extensions == null)
            result.extensions = new ArrayList<>();
        result.extensions.add(toExtensionVersionJson(extVersion));
    }

    private static boolean mismatch(String s1, String s2) {
        return s1 != null && s2 != null
                && !s1.isEmpty() && !s2.isEmpty()
                && !s1.equalsIgnoreCase(s2);
    }

    @Transactional(rollbackOn = ErrorResultException.class)
    public ResultJson createNamespace(NamespaceJson json, String tokenValue) {
        var namespaceIssue = validator.validateNamespace(json.name);
        if (namespaceIssue.isPresent()) {
            throw new ErrorResultException(namespaceIssue.get().toString());
        }
        var token = users.useAccessToken(tokenValue);
        if (token == null) {
            throw new ErrorResultException("Invalid access token.");
        }
        eclipse.checkPublisherAgreement(token.getUser());
        var namespace = repositories.findNamespace(json.name);
        if (namespace != null) {
            throw new ErrorResultException("Namespace already exists: " + namespace.getName());
        }

        namespace = new Namespace();
        namespace.setName(json.name);
        entityManager.persist(namespace);
        return ResultJson.success("Created namespace " + namespace.getName());
    }

    @Transactional(rollbackOn = ErrorResultException.class)
    public ExtensionJson publish(InputStream content, String tokenValue) throws ErrorResultException {
        try (var processor = new ExtensionProcessor(content)) {
            var token = users.useAccessToken(tokenValue);
            if (token == null || token.getUser() == null) {
                throw new ErrorResultException("Invalid access token.");
            }
            eclipse.checkPublisherAgreement(token.getUser());
            var extVersion = createExtensionVersion(processor, token.getUser(), token);
            storeResources(processor.getResources(extVersion), extVersion);
            processor.getExtensionDependencies().forEach(dep -> addDependency(dep, extVersion));
            processor.getBundledExtensions().forEach(dep -> addBundledExtension(dep, extVersion));

            search.updateSearchEntry(extVersion.getExtension());
            return toExtensionVersionJson(extVersion);
        }
    }

    private ExtensionVersion createExtensionVersion(ExtensionProcessor processor, UserData user, PersonalAccessToken token) {
        var namespaceName = processor.getNamespace();
        var namespace = repositories.findNamespace(namespaceName);
        if (namespace == null) {
            throw new ErrorResultException("Unknown publisher: " + namespaceName
                    + "\nUse the 'create-namespace' command to create a namespace corresponding to your publisher name.");
        }
        if (!users.hasPublishPermission(user, namespace)) {
            throw new ErrorResultException("Insufficient access rights for publisher: " + namespace.getName());
        }

        var extensionName = processor.getExtensionName();
        var nameIssue = validator.validateExtensionName(extensionName);
        if (nameIssue.isPresent()) {
            throw new ErrorResultException(nameIssue.get().toString());
        }
        var extVersion = processor.getMetadata();
        if (extVersion.getDisplayName() != null && extVersion.getDisplayName().trim().isEmpty()) {
            extVersion.setDisplayName(null);
        }
        extVersion.setTimestamp(TimeUtil.getCurrentUTC());
        extVersion.setPublishedWith(token);
        entityManager.persist(extVersion);

        var extension = repositories.findExtension(extensionName, namespace);
        if (extension == null) {
            extension = new Extension();
            extension.setName(extensionName);
            extension.setNamespace(namespace);
            extension.setLatest(extVersion);
            if (extVersion.isPreview())
                extension.setPreview(extVersion);
            entityManager.persist(extension);
        } else {
            if (repositories.findVersion(extVersion.getVersion(), extension) != null) {
                throw new ErrorResultException(
                        "Extension " + namespace.getName() + "." + extension.getName()
                        + " version " + extVersion.getVersion()
                        + " is already published.");
            }
            if (extension.getLatest() == null
                    || extension.getLatest().isPreview() && isGreater(extVersion, extension.getLatest())
                    || !extVersion.isPreview() && isLatestVersion(extVersion.getVersion(), false, extension)) {
                extension.setLatest(extVersion);
            }
            if (extVersion.isPreview() && isLatestVersion(extVersion.getVersion(), true, extension)) {
                extension.setPreview(extVersion);
            }
        }
        extVersion.setExtension(extension);
        var metadataIssues = validator.validateMetadata(extVersion);
        if (!metadataIssues.isEmpty()) {
            if (metadataIssues.size() == 1) {
                throw new ErrorResultException(metadataIssues.get(0).toString());
            }
            throw new ErrorResultException("Multiple issues were found in the extension metadata:\n"
                    + Joiner.on("\n").join(metadataIssues));
        }
        return extVersion;
    }

    private boolean isLatestVersion(String version, boolean preview, Extension extension) {
        var newSemver = new SemanticVersion(version);
        for (var publishedVersion : repositories.findVersions(extension, preview)) {
            var oldSemver = publishedVersion.getSemanticVersion();
            if (newSemver.compareTo(oldSemver) < 0)
                return false;
        }
        return true;
    }

    private boolean isGreater(ExtensionVersion v1, ExtensionVersion v2) {
        var sv1 = v1.getSemanticVersion();
        var sv2 = v2.getSemanticVersion();
        return sv1.compareTo(sv2) > 0;
    }

    private void storeResources(List<FileResource> resources, ExtensionVersion extVersion) {
        var extension = extVersion.getExtension();
        var namespace = extension.getNamespace();
        resources.forEach(resource -> {
            if (resource.getType().equals(FileResource.DOWNLOAD)) {
                resource.setName(namespace.getName() + "." + extension.getName() + "-" + extVersion.getVersion() + ".vsix");
            }
            if (storageUtil.shouldStoreExternally(resource) && googleStorage.isEnabled()) {
                googleStorage.uploadFile(resource);
            } else {
                resource.setStorageType(FileResource.STORAGE_DB);
            }
            entityManager.persist(resource);
        });
    }

    private void addDependency(String dependency, ExtensionVersion extVersion) {
        var split = dependency.split("\\.");
        if (split.length != 2 || split[0].isEmpty() || split[1].isEmpty()) {
            throw new ErrorResultException("Invalid 'extensionDependencies' format. Expected: '${namespace}.${name}'");
        }
        var extension = repositories.findExtension(split[1], split[0]);
        if (extension == null) {
            throw new ErrorResultException("Cannot resolve dependency: " + dependency);
        }
        var depList = extVersion.getDependencies();
        if (depList == null) {
            depList = new ArrayList<Extension>();
            extVersion.setDependencies(depList);
        }
        depList.add(extension);
    }

    private void addBundledExtension(String bundled, ExtensionVersion extVersion) {
        var split = bundled.split("\\.");
        if (split.length != 2 || split[0].isEmpty() || split[1].isEmpty()) {
            throw new ErrorResultException("Invalid 'extensionPack' format. Expected: '${namespace}.${name}'");
        }
        var extension = repositories.findExtension(split[1], split[0]);
        if (extension == null) {
            throw new ErrorResultException("Cannot resolve bundled extension: " + bundled);
        }
        var depList = extVersion.getBundledExtensions();
        if (depList == null) {
            depList = new ArrayList<Extension>();
            extVersion.setBundledExtensions(depList);
        }
        depList.add(extension);
    }

    @Transactional(rollbackOn = ResponseStatusException.class)
    public ResultJson postReview(ReviewJson review, String namespace, String extensionName) {
        var user = users.findLoggedInUser();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var extension = repositories.findExtension(extensionName, namespace);
        if (extension == null) {
            return ResultJson.error("Extension not found: " + namespace + "." + extensionName);
        }
        var activeReviews = repositories.findActiveReviews(extension, user);
        if (!activeReviews.isEmpty()) {
            return ResultJson.error("You must not submit more than one review for an extension.");
        }

        var extReview = new ExtensionReview();
        extReview.setExtension(extension);
        extReview.setActive(true);
        extReview.setTimestamp(TimeUtil.getCurrentUTC());
        extReview.setUser(user);
        extReview.setTitle(review.title);
        extReview.setComment(review.comment);
        extReview.setRating(review.rating);
        entityManager.persist(extReview);
        extension.setAverageRating(computeAverageRating(extension));
        search.updateSearchEntry(extension);
        return ResultJson.success("Added review for " + extension.getNamespace().getName() + "." + extension.getName());
    }

    @Transactional(rollbackOn = ResponseStatusException.class)
    public ResultJson deleteReview(String namespace, String extensionName) {
        var user = users.findLoggedInUser();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var extension = repositories.findExtension(extensionName, namespace);
        if (extension == null) {
            return ResultJson.error("Extension not found: " + namespace + "." + extensionName);
        }
        var activeReviews = repositories.findActiveReviews(extension, user);
        if (activeReviews.isEmpty()) {
            return ResultJson.error("You have not submitted any review yet.");
        }

        for (var extReview : activeReviews) {
            extReview.setActive(false);
        }
        extension.setAverageRating(computeAverageRating(extension));
        search.updateSearchEntry(extension);
        return ResultJson.success("Deleted review for " + extension.getNamespace().getName() + "." + extension.getName());
    }

    private Double computeAverageRating(Extension extension) {
        var activeReviews = repositories.findActiveReviews(extension);
        if (activeReviews.isEmpty()) {
            return null;
        }
        long sum = 0;
        long count = 0;
        for (var review : activeReviews) {
            sum += review.getRating();
            count++;
        }
        return (double) sum / count;
    }

    private SearchEntryJson toSearchEntry(ExtensionSearch searchItem, String serverUrl, SearchService.Options options) {
        var extension = entityManager.find(Extension.class, searchItem.id);
        if (extension == null)
            return null;
        var extVer = extension.getLatest();
        var entry = extVer.toSearchEntryJson();
        entry.url = createApiUrl(serverUrl, "api", entry.namespace, entry.name);
        entry.files = Maps.newLinkedHashMapWithExpectedSize(2);
        storageUtil.addFileUrls(extVer, serverUrl, entry.files, FileResource.DOWNLOAD, FileResource.ICON);
        if (options.includeAllVersions) {
            var allVersions = Lists.newArrayList(repositories.findVersions(extension));
            Collections.sort(allVersions, ExtensionVersion.SORT_COMPARATOR);
            entry.allVersions = CollectionUtil.map(allVersions, ev -> toVersionReference(ev, entry, serverUrl));
        }
        return entry;
    }

    private SearchEntryJson.VersionReference toVersionReference(ExtensionVersion extVersion, SearchEntryJson entry, String serverUrl) {
        var json = new SearchEntryJson.VersionReference();
        json.version = extVersion.getVersion();
        json.engines = extVersion.getEnginesMap();
        json.url = createApiUrl(serverUrl, "api", entry.namespace, entry.name, extVersion.getVersion());
        json.files = Maps.newLinkedHashMapWithExpectedSize(1);
        storageUtil.addFileUrls(extVersion, serverUrl, json.files, FileResource.DOWNLOAD);
        return json;
    }

    private ExtensionJson toExtensionVersionJson(ExtensionVersion extVersion) {
        var extension = extVersion.getExtension();
        var json = extVersion.toExtensionJson();
        json.versionAlias = new ArrayList<>(2);
        if (extVersion == extension.getLatest())
            json.versionAlias.add("latest");
        if (extVersion == extension.getPreview())
            json.versionAlias.add("preview");
        json.namespaceAccess = getAccessString(extension.getNamespace());
        if (NamespaceJson.RESTRICTED_ACCESS.equals(json.namespaceAccess))
            json.unrelatedPublisher = isUnrelatedPublisher(extVersion);
        json.reviewCount = repositories.countActiveReviews(extension);
        var serverUrl = UrlUtil.getBaseUrl();
        json.namespaceUrl = createApiUrl(serverUrl, "api", json.namespace);
        json.reviewsUrl = createApiUrl(serverUrl, "api", json.namespace, json.name, "reviews");

        var allVersions = CollectionUtil.map(repositories.getVersionStrings(extension), v -> new SemanticVersion(v));
        Collections.sort(allVersions, Collections.reverseOrder());
        json.allVersions = Maps.newLinkedHashMapWithExpectedSize(allVersions.size() + 2);
        if (extension.getLatest() != null)
            json.allVersions.put("latest", createApiUrl(serverUrl, "api", json.namespace, json.name, "latest"));
        if (extension.getPreview() != null)
            json.allVersions.put("preview", createApiUrl(serverUrl, "api", json.namespace, json.name, "preview"));
        for (var version : allVersions) {
            String url = createApiUrl(serverUrl, "api", json.namespace, json.name, version.toString());
            json.allVersions.put(version.toString(), url);
        }
    
        json.files = Maps.newLinkedHashMapWithExpectedSize(6);
        storageUtil.addFileUrls(extVersion, serverUrl, json.files,
                FileResource.DOWNLOAD, FileResource.MANIFEST, FileResource.ICON, FileResource.README, FileResource.LICENSE, FileResource.CHANGELOG);
    
        if (json.dependencies != null) {
            json.dependencies.forEach(ref -> {
                ref.url = createApiUrl(serverUrl, "api", ref.namespace, ref.extension);
            });
        }
        if (json.bundledExtensions != null) {
            json.bundledExtensions.forEach(ref -> {
                ref.url = createApiUrl(serverUrl, "api", ref.namespace, ref.extension);
            });
        }
        return json;
    }

    private boolean isUnrelatedPublisher(ExtensionVersion extVersion) {
        if (extVersion.getPublishedWith() == null)
            return false;
        var user = extVersion.getPublishedWith().getUser();
        var namespace = extVersion.getExtension().getNamespace();
        var memberships = repositories.countMemberships(user, namespace);
        return memberships == 0;
    }

}