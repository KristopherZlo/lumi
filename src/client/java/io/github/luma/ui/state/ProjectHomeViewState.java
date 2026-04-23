package io.github.luma.ui.state;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import java.util.List;

public record ProjectHomeViewState(
        BuildProject project,
        List<ProjectVersion> versions,
        List<ProjectVariant> variants,
        PendingChangeSummary pendingChanges,
        boolean hasRecoveryDraft,
        OperationSnapshot operationSnapshot,
        ProjectAdvancedViewState advanced,
        String status
) {
}
