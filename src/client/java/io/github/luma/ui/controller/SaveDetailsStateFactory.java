package io.github.luma.ui.controller;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.service.VersionLineageService;
import io.github.luma.ui.state.SaveDetailsViewState;
import java.util.List;

public final class SaveDetailsStateFactory {

    private final VersionLineageService versionLineageService;

    public SaveDetailsStateFactory() {
        this(new VersionLineageService());
    }

    SaveDetailsStateFactory(VersionLineageService versionLineageService) {
        this.versionLineageService = versionLineageService;
    }

    public SaveDetailsViewState create(
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            String selectedVersionId,
            RecoveryDraft recoveryDraft,
            OperationSnapshot operationSnapshot,
            String status
    ) {
        return new SaveDetailsViewState(
                project,
                versions,
                variants,
                this.resolveSelectedVersion(versions, variants, project == null ? "" : project.activeVariantId(), selectedVersionId),
                recoveryDraft,
                operationSnapshot,
                status == null || status.isBlank() ? "luma.status.project_ready" : status
        );
    }

    private ProjectVersion resolveSelectedVersion(
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            String activeVariantId,
            String selectedVersionId
    ) {
        if (versions.isEmpty()) {
            return null;
        }

        if (selectedVersionId != null && !selectedVersionId.isBlank()) {
            for (ProjectVersion version : versions) {
                if (version.id().equals(selectedVersionId)) {
                    return version;
                }
            }
        }

        ProjectVersion activeHead = this.versionLineageService.resolveVariantHead(versions, variants, activeVariantId);
        return activeHead != null ? activeHead : versions.getFirst();
    }
}
