package io.github.luma.ui.state;

import io.github.luma.domain.model.ProjectIntegrityReport;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.integration.common.IntegrationStatus;
import java.util.List;

public record ProjectAdvancedViewState(
        ProjectIntegrityReport integrityReport,
        List<IntegrationStatus> integrations,
        List<RecoveryJournalEntry> journal
) {
}
