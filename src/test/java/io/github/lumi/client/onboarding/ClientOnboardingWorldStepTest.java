package io.github.lumi.client.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClientOnboardingWorldStepTest {
    @Test
    void countsOnlyNewPendingBlocks() {
        var old = new HistorySnapshotPayload.PendingBlock(1, 2, 3);
        var added = new HistorySnapshotPayload.PendingBlock(4, 5, 6);

        assertEquals(0, ClientOnboardingWorldStep.trackedEdits(
                Set.of(old), List.of(old)));
        assertEquals(1, ClientOnboardingWorldStep.trackedEdits(
                Set.of(old), List.of(old, added, added)));
        assertEquals(1, ClientOnboardingWorldStep.trackedEdits(
                Set.of(old), List.of(added)));
    }

    @Test
    void clampsTheRequiredPreviewHoldProgress() {
        assertEquals(0.0F, ClientOnboardingWorldStep.holdProgress(-1L));
        assertEquals(0.5F,
                ClientOnboardingWorldStep.holdProgress(750_000_000L));
        assertEquals(1.0F,
                ClientOnboardingWorldStep.holdProgress(1_500_000_000L));
        assertEquals(1.0F,
                ClientOnboardingWorldStep.holdProgress(2_000_000_000L));
    }
}
