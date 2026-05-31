package io.github.luma.minecraft.world;

import io.github.luma.domain.model.WorldMutationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TntReplayActivationPolicyTest {

    private final TntReplayActivationPolicy policy = new TntReplayActivationPolicy();

    @Test
    void suppressesReplayNeighborActivationDuringInternalApply() {
        assertTrue(this.policy.shouldSuppressActivation(false, WorldMutationSource.RESTORE, true));
        assertTrue(this.policy.shouldSuppressActivation(false, WorldMutationSource.BLOCK_UPDATE, true));
    }

    @Test
    void keepsRealPlayerAndToolActivationUntouched() {
        assertFalse(this.policy.shouldSuppressActivation(false, WorldMutationSource.PLAYER, false));
        assertFalse(this.policy.shouldSuppressActivation(false, WorldMutationSource.EXPLOSIVE, false));
        assertFalse(this.policy.shouldSuppressActivation(false, WorldMutationSource.AXIOM, true));
        assertFalse(this.policy.shouldSuppressActivation(true, WorldMutationSource.RESTORE, true));
    }
}
