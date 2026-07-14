package io.github.luma.ui.state;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.HistoryProtectionStatus;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import java.util.List;
import java.util.Map;

public record ProjectHomeViewState(
        BuildProject project,
        List<ProjectVersion> versions,
        List<ProjectVariant> variants,
        PendingChangeSummary pendingChanges,
        boolean hasRecoveryDraft,
        OperationSnapshot operationSnapshot,
        ProjectAdvancedViewState advanced,
        String status,
        boolean hasRestoreReturnPoint,
        Map<String, Integer> zoneColorByVersionId,
        HistoryProtectionStatus historyProtection,
        boolean hasSafetyChanges
) {

    public ProjectHomeViewState(
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            PendingChangeSummary pendingChanges,
            boolean hasRecoveryDraft,
            OperationSnapshot operationSnapshot,
            ProjectAdvancedViewState advanced,
            String status,
            boolean hasRestoreReturnPoint,
            Map<String, Integer> zoneColorByVersionId
    ) {
        this(
                project, versions, variants, pendingChanges, hasRecoveryDraft, operationSnapshot, advanced, status,
                hasRestoreReturnPoint, zoneColorByVersionId, HistoryProtectionStatus.protectedStatus(), false
        );
    }

    public ProjectHomeViewState(
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            PendingChangeSummary pendingChanges,
            boolean hasRecoveryDraft,
            OperationSnapshot operationSnapshot,
            ProjectAdvancedViewState advanced,
            String status
    ) {
        this(project, versions, variants, pendingChanges, hasRecoveryDraft, operationSnapshot, advanced, status, false);
    }

    public ProjectHomeViewState(
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            PendingChangeSummary pendingChanges,
            boolean hasRecoveryDraft,
            OperationSnapshot operationSnapshot,
            ProjectAdvancedViewState advanced,
            String status,
            boolean hasRestoreReturnPoint
    ) {
        this(project, versions, variants, pendingChanges, hasRecoveryDraft, operationSnapshot, advanced, status, hasRestoreReturnPoint, Map.of());
    }

    public ProjectHomeViewState {
        zoneColorByVersionId = zoneColorByVersionId == null ? Map.of() : Map.copyOf(zoneColorByVersionId);
        historyProtection = historyProtection == null
                ? HistoryProtectionStatus.protectedStatus() : historyProtection;
    }

    public Integer zoneColor(ProjectVersion version) {
        return version == null ? null : this.zoneColorByVersionId.get(version.id());
    }

    public boolean hasUnsavedChanges() {
        return this.hasSafetyChanges || (this.pendingChanges != null && !this.pendingChanges.isEmpty());
    }
}
