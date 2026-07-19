package io.github.lumi.client.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientOnboardingWorldStepTest {
    @Test
    void countsOnlyNewPendingBlocks() {
        assertEquals(0, ClientOnboardingWorldStep.trackedEdits(-1, 5));
        assertEquals(0, ClientOnboardingWorldStep.trackedEdits(5, 3));
        assertEquals(4, ClientOnboardingWorldStep.trackedEdits(5, 9));
    }

    @Test
    void previewRequiresBothTheKeyAndVisibleOverlay() {
        assertFalse(ClientOnboardingWorldStep.previewHoldActive(true, false));
        assertFalse(ClientOnboardingWorldStep.previewHoldActive(false, true));
        assertTrue(ClientOnboardingWorldStep.previewHoldActive(true, true));
    }
}
