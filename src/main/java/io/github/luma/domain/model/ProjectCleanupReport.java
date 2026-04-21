package io.github.luma.domain.model;

import java.util.List;

public record ProjectCleanupReport(
        boolean dryRun,
        List<ProjectCleanupCandidate> candidates,
        List<String> warnings,
        long reclaimedBytes
) {
}
