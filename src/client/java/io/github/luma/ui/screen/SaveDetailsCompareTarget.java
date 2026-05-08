package io.github.luma.ui.screen;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.CompareScreenController;
import java.util.List;

final class SaveDetailsCompareTarget {

    private SaveDetailsCompareTarget() {
    }

    static Target seeChangesTarget(
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion version,
            RecoveryDraft recoveryDraft
    ) {
        if (version == null) {
            return new Target("", "", "");
        }

        String parentVersionId = parentVersionId(versions, version.id());
        if (usesSavedHeadDiff(project, variants, version, recoveryDraft) && !parentVersionId.isBlank()) {
            return new Target(parentVersionId, version.id(), version.id());
        }

        return new Target(version.id(), CompareScreenController.CURRENT_WORLD_REFERENCE, version.id());
    }

    private static boolean usesSavedHeadDiff(
            BuildProject project,
            List<ProjectVariant> variants,
            ProjectVersion version,
            RecoveryDraft recoveryDraft
    ) {
        return project != null
                && version.variantId().equals(project.activeVariantId())
                && ProjectUiSupport.isVariantHead(variants, version)
                && (recoveryDraft == null || recoveryDraft.isEmpty());
    }

    private static String parentVersionId(List<ProjectVersion> versions, String versionId) {
        if (versions == null || versionId == null || versionId.isBlank()) {
            return "";
        }
        for (ProjectVersion version : versions) {
            if (versionId.equals(version.id())) {
                return version.parentVersionId() == null ? "" : version.parentVersionId();
            }
        }
        return "";
    }

    record Target(String leftReference, String rightReference, String contextVersionId) {
    }
}
