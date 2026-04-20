package io.github.luma.ui.state;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.MaterialDeltaEntry;
import io.github.luma.domain.model.ProjectIntegrityReport;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.VersionDiff;
import io.github.luma.integration.common.IntegrationStatus;
import java.util.List;

public record ProjectViewState(
        BuildProject project,
        List<ProjectVersion> versions,
        List<ProjectVariant> variants,
        List<RecoveryJournalEntry> journal,
        RecoveryDraft recoveryDraft,
        ProjectVersion selectedVersion,
        VersionDiff selectedVersionDiff,
        List<MaterialDeltaEntry> materialDelta,
        List<IntegrationStatus> integrations,
        ProjectIntegrityReport integrityReport,
        ProjectTab selectedTab,
        String status
) {
}
