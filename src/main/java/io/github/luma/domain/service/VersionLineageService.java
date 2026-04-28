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

    public Map<String, ProjectVersion> versionMap(List<ProjectVersion> versions) {
        Map<String, ProjectVersion> versionMap = new LinkedHashMap<>();
        if (versions == null) {
            return Map.of();
        }
        for (ProjectVersion version : versions) {
            versionMap.put(version.id(), version);
        }
        return versionMap;
    }

    public ProjectVersion commonAncestor(Map<String, ProjectVersion> versionMap, ProjectVersion left, ProjectVersion right) {
        Set<String> leftAncestors = new LinkedHashSet<>();
        ProjectVersion cursor = left;
        while (cursor != null) {
            leftAncestors.add(cursor.id());
            cursor = this.parent(versionMap, cursor);
        }

        cursor = right;
        while (cursor != null) {
            if (leftAncestors.contains(cursor.id())) {
                return cursor;
            }
            cursor = this.parent(versionMap, cursor);
        }

        throw new IllegalArgumentException("Versions do not share a common ancestor");
    }

    public String sharedSavedAncestorId(
            Map<String, ProjectVersion> targetVersionMap,
            Map<String, ProjectVersion> sourceVersionMap,
            String sourceHeadVersionId
    ) {
        ProjectVersion cursor = sourceVersionMap.get(sourceHeadVersionId);
        while (cursor != null) {
            ProjectVersion targetCandidate = targetVersionMap.get(cursor.id());
            if (targetCandidate != null && targetCandidate.equals(cursor)) {
                return targetCandidate.id();
            }
            cursor = this.parent(sourceVersionMap, cursor);
        }
        throw new IllegalArgumentException("Imported variant does not share a common saved ancestor with the target project");
    }

    public List<ProjectVersion> pathFromAncestor(
            Map<String, ProjectVersion> versionMap,
            ProjectVersion ancestor,
            ProjectVersion target
    ) {
        if (ancestor == null || target == null) {
            return List.of();
        }
        return this.pathFromAncestor(versionMap, ancestor.id(), target.id());
    }

    public List<ProjectVersion> pathFromAncestor(
            Map<String, ProjectVersion> versionMap,
            String ancestorVersionId,
            String targetHeadVersionId
    ) {
        List<ProjectVersion> reversed = new ArrayList<>();
        ProjectVersion cursor = versionMap.get(targetHeadVersionId);
        while (cursor != null && !cursor.id().equals(ancestorVersionId)) {
            reversed.add(cursor);
            cursor = this.parent(versionMap, cursor);
        }
        List<ProjectVersion> path = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            path.add(reversed.get(index));
        }
        return List.copyOf(path);
    }

    public boolean isAncestor(Map<String, ProjectVersion> versionMap, String ancestorVersionId, String descendantVersionId) {
        ProjectVersion cursor = versionMap.get(descendantVersionId);
        while (cursor != null) {
            if (cursor.id().equals(ancestorVersionId)) {
                return true;
            }
            cursor = this.parent(versionMap, cursor);
        }
        return false;
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

    private ProjectVersion parent(Map<String, ProjectVersion> versionMap, ProjectVersion version) {
        return version.parentVersionId() == null || version.parentVersionId().isBlank()
                ? null
                : versionMap.get(version.parentVersionId());
    }
}
