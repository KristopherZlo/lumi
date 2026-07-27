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
        assertFalse(freeze.isEntityAdditionAllowed());
        freeze.runAuthorized(() -> {
            assertTrue(freeze.isMutationAllowed());
            assertTrue(freeze.isAuthorizedMutation());
            assertFalse(freeze.isEntityAdditionAllowed());
            freeze.runAuthorized(() -> assertTrue(freeze.isMutationAllowed()));
        });
        freeze.runAuthorizedEntityAddition(() -> {
            assertTrue(freeze.isMutationAllowed());
            assertTrue(freeze.isAuthorizedMutation());
            assertTrue(freeze.isEntityAdditionAllowed());
        });
        assertFalse(freeze.isMutationAllowed());
        assertFalse(freeze.isAuthorizedMutation());
        assertFalse(freeze.isEntityAdditionAllowed());

        lease.release();
        assertFalse(freeze.isFrozen());
        assertTrue(freeze.isMutationAllowed());
        assertTrue(freeze.isEntityAdditionAllowed());
        assertThrows(IllegalStateException.class, lease::release);
    }

    @Test
    void permitsOnlyOneFreezeLease() {
        DimensionFreezeState freeze = new DimensionFreezeState();
        freeze.acquire();
        assertThrows(IllegalStateException.class, freeze::acquire);
    }
}
