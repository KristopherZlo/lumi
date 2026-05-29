package io.github.luma.ui.controller;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.state.BranchCreationDialogState;
import io.github.luma.ui.state.ProjectHomeViewState;
import java.util.List;

public final class BranchCreationDialogStateFactory {

    public BranchCreationDialogState create(ProjectHomeViewState home, String baseVersionId, String branchName) {
        if (home == null || home.project() == null || baseVersionId == null || baseVersionId.isBlank()) {
            return BranchCreationDialogState.hidden(branchName);
        }

        return this.create(
                home.versions(),
                home.variants(),
                home.operationSnapshot(),
                baseVersionId,
                branchName
        );
    }

    public BranchCreationDialogState create(
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            io.github.luma.domain.model.OperationSnapshot operationSnapshot,
            String baseVersionId,
            String branchName
    ) {
        if (baseVersionId == null || baseVersionId.isBlank()) {
            return BranchCreationDialogState.hidden(branchName);
        }

        ProjectVersion baseVersion = this.versionFor(versions, baseVersionId);
        if (baseVersion == null) {
            return BranchCreationDialogState.hidden(branchName);
        }
        return new BranchCreationDialogState(
                baseVersion,
                this.variantFor(variants, baseVersion.variantId()),
                branchName,
                ScreenOperationStateSupport.blocksMutationActions(operationSnapshot)
        );
    }

    private ProjectVersion versionFor(List<ProjectVersion> versions, String versionId) {
        if (versions == null) {
            return null;
        }
        for (ProjectVersion version : versions) {
            if (version != null && versionId.equals(version.id())) {
                return version;
            }
        }
        return null;
    }

    private ProjectVariant variantFor(List<ProjectVariant> variants, String variantId) {
        if (variantId == null || variantId.isBlank()) {
            return null;
        }
        if (variants == null) {
            return null;
        }
        for (ProjectVariant variant : variants) {
            if (variant != null && variantId.equals(variant.id())) {
                return variant;
            }
        }
        return null;
    }
}
