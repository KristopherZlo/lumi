package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DimensionFreezeStateTest {
    @Test
    void blocksOrdinaryMutationButAllowsScopedRestoreApply() {
        DimensionFreezeState freeze = new DimensionFreezeState();
        DimensionFreeze.Lease lease = freeze.acquire();

        assertTrue(freeze.isFrozen());
        assertFalse(freeze.isMutationAllowed());
        assertFalse(freeze.isAuthorizedMutation());
        freeze.runAuthorized(() -> {
            assertTrue(freeze.isMutationAllowed());
            assertTrue(freeze.isAuthorizedMutation());
            freeze.runAuthorized(() -> assertTrue(freeze.isMutationAllowed()));
        });
        assertFalse(freeze.isMutationAllowed());
        assertFalse(freeze.isAuthorizedMutation());

        lease.release();
        assertFalse(freeze.isFrozen());
        assertTrue(freeze.isMutationAllowed());
        assertThrows(IllegalStateException.class, lease::release);
    }

    @Test
    void permitsOnlyOneFreezeLease() {
        DimensionFreezeState freeze = new DimensionFreezeState();
        freeze.acquire();
        assertThrows(IllegalStateException.class, freeze::acquire);
    }
}
