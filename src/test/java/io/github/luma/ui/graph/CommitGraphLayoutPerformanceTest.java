package io.github.luma.ui.graph;

import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitGraphLayoutPerformanceTest {

    @Test
    void commitGraphBuildHandlesLargeBranchHistoryWithinBudget() {
        SyntheticHistory history = this.syntheticHistory();

        for (int warmup = 0; warmup < 3; warmup++) {
            CommitGraphLayout.build(history.versions(), history.variants(), "main");
        }

        long startedAt = System.nanoTime();
        List<CommitGraphNode> nodes = List.of();
        for (int iteration = 0; iteration < 8; iteration++) {
            nodes = CommitGraphLayout.build(history.versions(), history.variants(), "main");
        }
        long elapsedNanos = System.nanoTime() - startedAt;

        assertFalse(nodes.isEmpty());
        assertWithin(Duration.ofMillis(1800), elapsedNanos, "Commit graph layout regressed");
    }

    private SyntheticHistory syntheticHistory() {
        List<ProjectVersion> versions = new ArrayList<>();
        List<ProjectVariant> variants = new ArrayList<>();
        Instant baseTime = Instant.parse("2026-04-21T00:00:00Z");

        String previousMainId = "";
        for (int index = 1; index <= 1800; index++) {
            String versionId = versionId("main", index);
            versions.add(this.version(versionId, "main", previousMainId, baseTime.plusSeconds(index)));
            previousMainId = versionId;

            if (index % 180 == 0) {
                String branchId = "branch-" + (index / 180);
                String branchParent = versionId;
                String branchHead = branchParent;
                for (int branchIndex = 1; branchIndex <= 80; branchIndex++) {
                    branchHead = versionId(branchId, branchIndex);
                    versions.add(this.version(
                            branchHead,
                            branchId,
                            branchParent,
                            baseTime.plusSeconds(10_000L + (index * 10L) + branchIndex)
                    ));
                    branchParent = branchHead;
                }
                variants.add(new ProjectVariant(branchId, branchId, versionId, branchHead, false, baseTime.plusSeconds(index)));
            }
        }

        variants.add(new ProjectVariant("main", "main", "v-main-0001", previousMainId, true, baseTime));
        return new SyntheticHistory(List.copyOf(versions), List.copyOf(variants));
    }

    private ProjectVersion version(String id, String variantId, String parentId, Instant createdAt) {
        return new ProjectVersion(
                id,
                "project",
                variantId,
                parentId == null ? "" : parentId,
                "",
                List.of("patch-" + id),
                VersionKind.MANUAL,
                "Zlo",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                createdAt
        );
    }

    private static String versionId(String variantId, int index) {
        return "v-" + variantId + "-" + String.format("%04d", index);
    }

    private static void assertWithin(Duration budget, long elapsedNanos, String message) {
        assertTrue(
                elapsedNanos <= budget.toNanos(),
                message + ": expected <= " + budget.toMillis() + " ms but was " + Duration.ofNanos(elapsedNanos).toMillis() + " ms"
        );
    }

    private record SyntheticHistory(List<ProjectVersion> versions, List<ProjectVariant> variants) {
    }
}
