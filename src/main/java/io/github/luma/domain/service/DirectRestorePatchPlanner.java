package io.github.luma.domain.service;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plans direct patch replay between the active branch head and a restore target.
 */
final class DirectRestorePatchPlanner {

    private final VersionLineageService lineageService = new VersionLineageService();

    List<ProjectVersion> patchVersions(
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion targetVersion
    ) {
        DirectRestorePatchPlan plan = this.plan(project, versions, variants, targetVersion);
        if (plan == null || plan.isDivergent()) {
            return null;
        }
        return plan.allVersions();
    }

    DirectRestorePatchPlan applicablePlan(
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion targetVersion
    ) {
        DirectRestorePatchPlan plan = this.plan(project, versions, variants, targetVersion);
        return plan == null || plan.isDivergent() ? null : plan;
    }

    DirectRestorePatchPlan plan(
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion targetVersion
    ) {
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(project.activeVariantId()))
                .findFirst()
                .orElse(null);
        if (activeVariant == null
                || activeVariant.headVersionId() == null
                || activeVariant.headVersionId().isBlank()
                || targetVersion == null) {
            return null;
        }

        Map<String, ProjectVersion> versionMap = this.lineageService.versionMap(versions);
        String headVersionId = activeVariant.headVersionId();
        if (targetVersion.id().equals(headVersionId)) {
            return DirectRestorePatchPlan.empty();
        }

        ProjectVersion headVersion = versionMap.get(headVersionId);
        if (headVersion == null) {
            return null;
        }

        if (this.lineageService.isAncestor(versionMap, targetVersion.id(), headVersionId)) {
            List<ProjectVersion> reverseVersions = pathFromHeadToAncestor(versionMap, headVersion, targetVersion.id());
            return reverseVersions == null ? null : new DirectRestorePatchPlan(reverseVersions, List.of());
        }

        if (this.lineageService.isAncestor(versionMap, headVersionId, targetVersion.id())) {
            return new DirectRestorePatchPlan(
                    List.of(),
                    this.lineageService.pathFromAncestor(versionMap, headVersionId, targetVersion.id())
            );
        }

        try {
            ProjectVersion ancestor = this.lineageService.commonAncestor(versionMap, headVersion, targetVersion);
            List<ProjectVersion> reverseVersions = pathFromHeadToAncestor(versionMap, headVersion, ancestor.id());
            if (reverseVersions == null) {
                return null;
            }
            return new DirectRestorePatchPlan(
                    reverseVersions,
                    this.lineageService.pathFromAncestor(versionMap, ancestor, targetVersion)
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static List<ProjectVersion> pathFromHeadToAncestor(
            Map<String, ProjectVersion> versionMap,
            ProjectVersion headVersion,
            String ancestorVersionId
    ) {
        List<ProjectVersion> directVersions = new ArrayList<>();
        ProjectVersion cursor = headVersion;
        while (cursor != null && !cursor.id().equals(ancestorVersionId)) {
            directVersions.add(cursor);
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }
        return cursor == null ? null : directVersions;
    }
}
