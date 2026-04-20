package io.github.luma.domain.service;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves reachable versions from the current set of variant heads.
 *
 * <p>The history model is a single-parent DAG, so a branch head reset or amend
 * should only expose the lineage still reachable from the active variant heads.
 * Detached descendants remain on disk for safety, but they should no longer be
 * treated as part of the live branch history.
 */
public final class VersionLineageService {

    public Set<String> reachableVersionIds(List<ProjectVersion> versions, List<ProjectVariant> variants) {
        if (versions == null || versions.isEmpty()) {
            return Set.of();
        }
        if (variants == null || variants.isEmpty()) {
            return versions.stream().map(ProjectVersion::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        Map<String, ProjectVersion> versionMap = this.versionMap(versions);
        LinkedHashSet<String> reachable = new LinkedHashSet<>();
        for (ProjectVariant variant : variants) {
            reachable.addAll(this.reachableVersionIds(versionMap, variant.headVersionId()));
        }
        return Set.copyOf(reachable);
    }

    public Set<String> reachableVersionIds(List<ProjectVersion> versions, String headVersionId) {
        if (versions == null || versions.isEmpty() || headVersionId == null || headVersionId.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(this.reachableVersionIds(this.versionMap(versions), headVersionId));
    }

    public List<ProjectVersion> reachableVersions(List<ProjectVersion> versions, List<ProjectVariant> variants) {
        if (versions == null || versions.isEmpty()) {
            return List.of();
        }

        Set<String> reachable = this.reachableVersionIds(versions, variants);
        List<ProjectVersion> visible = new ArrayList<>();
        for (ProjectVersion version : versions) {
            if (reachable.contains(version.id())) {
                visible.add(version);
            }
        }
        return List.copyOf(visible);
    }

    public ProjectVersion resolveVariantHead(List<ProjectVersion> versions, List<ProjectVariant> variants, String variantId) {
        if (versions == null || versions.isEmpty() || variants == null || variants.isEmpty() || variantId == null || variantId.isBlank()) {
            return null;
        }

        String headVersionId = "";
        for (ProjectVariant variant : variants) {
            if (variant.id().equals(variantId)) {
                headVersionId = variant.headVersionId();
                break;
            }
        }
        if (headVersionId == null || headVersionId.isBlank()) {
            return null;
        }

        for (ProjectVersion version : versions) {
            if (version.id().equals(headVersionId)) {
                return version;
            }
        }
        return null;
    }

    private Map<String, ProjectVersion> versionMap(List<ProjectVersion> versions) {
        Map<String, ProjectVersion> versionMap = new LinkedHashMap<>();
        for (ProjectVersion version : versions) {
            versionMap.put(version.id(), version);
        }
        return versionMap;
    }

    private Set<String> reachableVersionIds(Map<String, ProjectVersion> versionMap, String headVersionId) {
        LinkedHashSet<String> reachable = new LinkedHashSet<>();
        if (headVersionId == null || headVersionId.isBlank()) {
            return reachable;
        }

        ProjectVersion cursor = versionMap.get(headVersionId);
        while (cursor != null && reachable.add(cursor.id())) {
            if (cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()) {
                break;
            }
            cursor = versionMap.get(cursor.parentVersionId());
        }
        return reachable;
    }
}
