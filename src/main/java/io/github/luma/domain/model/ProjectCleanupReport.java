package io.github.luma.domain.model;

import java.util.List;

public record ProjectCleanupReport(
        boolean dryRun,
        List<ProjectCleanupCandidate> candidates,
        List<String> warnings,
        long reclaimedBytes
) {

    public ProjectCleanupReport {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        reclaimedBytes = Math.max(0L, reclaimedBytes);
    }
}
