package io.github.luma.domain.model;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryDraftSummaryTest {

    @Test
    void summaryUsesVariantHeadAndCountsTouchedChunks() {
        RecoveryDraft draft = new RecoveryDraft(
                "project-1",
                "main",
                "v0003",
                "tester",
                WorldMutationSource.PLAYER,
                Instant.parse("2026-04-21T09:00:00Z"),
                Instant.parse("2026-04-21T09:05:00Z"),
                List.of(
                        change(0, 64, 0),
                        change(1, 65, 1),
                        change(32, 70, 0)
                )
        );

        RecoveryDraftSummary summary = RecoveryDraftSummary.from(
                draft,
                List.of(new ProjectVariant("main", "main", "v0003", "v0005", true, Instant.parse("2026-04-21T08:00:00Z")))
        );

        assertEquals("main", summary.variantId());
        assertEquals("v0003", summary.baseVersionId());
        assertEquals("v0005", summary.headVersionId());
        assertEquals(3, summary.changeCount());
        assertEquals(2, summary.touchedChunkCount());
    }

    @Test
    void summaryFallsBackToBaseVersionWhenVariantHeadIsUnknown() {
        RecoveryDraft draft = new RecoveryDraft(
                "project-1",
                "alt",
                "v0012",
                "tester",
                WorldMutationSource.PLAYER,
                Instant.parse("2026-04-21T09:00:00Z"),
                Instant.parse("2026-04-21T09:05:00Z"),
                List.of(change(0, 64, 0))
        );

        RecoveryDraftSummary summary = RecoveryDraftSummary.from(draft, List.of());

        assertEquals("v0012", summary.headVersionId());
        assertEquals(1, summary.touchedChunkCount());
    }

    private static StoredBlockChange change(int x, int y, int z) {
        StatePayload air = new StatePayload(null, null);
        return new StoredBlockChange(new BlockPoint(x, y, z), air, air);
    }
}
