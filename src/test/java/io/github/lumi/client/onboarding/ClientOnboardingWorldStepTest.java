package io.github.lumi.client.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void previewRequiresBothTheKeyAndVisibleOverlay() {
        assertFalse(ClientOnboardingWorldStep.previewHoldActive(true, false));
        assertFalse(ClientOnboardingWorldStep.previewHoldActive(false, true));
        assertTrue(ClientOnboardingWorldStep.previewHoldActive(true, true));
    }
}
