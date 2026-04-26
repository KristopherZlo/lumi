package io.github.luma.ui.state;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.MaterialDeltaEntry;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.VersionDiff;
import java.util.List;

public record SaveDetailsViewState(
        BuildProject project,
        List<ProjectVersion> versions,
        List<ProjectVariant> variants,
        ProjectVersion selectedVersion,
        VersionDiff selectedVersionDiff,
        List<MaterialDeltaEntry> materialDelta,
        RecoveryDraft recoveryDraft,
        OperationSnapshot operationSnapshot,
        String status
) {
}
