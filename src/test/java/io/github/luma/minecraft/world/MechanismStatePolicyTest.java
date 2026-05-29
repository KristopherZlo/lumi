package io.github.luma.minecraft.world;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MechanismStatePolicyTest {

    private final MechanismStatePolicy policy = new MechanismStatePolicy();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void classifiesRedstoneAndMechanismBlocks() {
        assertTrue(this.policy.isMechanismRelevant(Blocks.REDSTONE_WIRE.defaultBlockState()));
        assertTrue(this.policy.isMechanismRelevant(Blocks.REDSTONE_TORCH.defaultBlockState()));
        assertTrue(this.policy.isMechanismRelevant(Blocks.REDSTONE_WALL_TORCH.defaultBlockState()));
        assertTrue(this.policy.isMechanismRelevant(Blocks.REPEATER.defaultBlockState()));
        assertTrue(this.policy.isMechanismRelevant(Blocks.COMPARATOR.defaultBlockState()));
        assertTrue(this.policy.isMechanismRelevant(Blocks.REDSTONE_LAMP.defaultBlockState()));
        assertTrue(this.policy.isMechanismRelevant(Blocks.OBSERVER.defaultBlockState()));
        assertTrue(this.policy.isMechanismRelevant(Blocks.DISPENSER.defaultBlockState()));
        assertTrue(this.policy.isMechanismRelevant(Blocks.DROPPER.defaultBlockState()));
        assertTrue(this.policy.isMechanismRelevant(Blocks.PISTON.defaultBlockState()));
        assertTrue(this.policy.isMechanismRelevant(Blocks.STICKY_PISTON.defaultBlockState()));
        assertTrue(this.policy.isMechanismRelevant(Blocks.PISTON_HEAD.defaultBlockState()));
        assertTrue(this.policy.isMechanismRelevant(Blocks.MOVING_PISTON.defaultBlockState()));
    }

    @Test
    void classifiesPlayerInputControlsSeparately() {
        assertTrue(this.policy.isPlayerInputControl(Blocks.LEVER.defaultBlockState()));
        assertTrue(this.policy.isPlayerInputControl(Blocks.STONE_BUTTON.defaultBlockState()));
        assertTrue(this.policy.isPlayerInputControl(Blocks.OAK_BUTTON.defaultBlockState()));
        assertTrue(this.policy.isPlayerInputControl(Blocks.STONE_PRESSURE_PLATE.defaultBlockState()));
        assertTrue(this.policy.isPlayerInputControl(Blocks.TRIPWIRE.defaultBlockState()));
        assertTrue(this.policy.isPlayerInputControl(Blocks.TRIPWIRE_HOOK.defaultBlockState()));
    }

    @Test
    void excludesOrdinaryBlocks() {
        assertFalse(this.policy.isMechanismRelevant(Blocks.STONE.defaultBlockState()));
        assertFalse(this.policy.isPlayerInputControl(Blocks.STONE.defaultBlockState()));
        assertFalse(this.policy.shouldScopeBlockUpdate(Blocks.STONE.defaultBlockState()));
        assertFalse(this.policy.shouldGuardExactReplay(Blocks.STONE.defaultBlockState()));
        assertFalse(this.policy.shouldSuppressReplayCallbacks(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void keepsExistingReplayGuardShape() {
        assertTrue(this.policy.shouldGuardExactReplay(Blocks.REDSTONE_WIRE.defaultBlockState()));
        assertTrue(this.policy.shouldGuardExactReplay(Blocks.REPEATER.defaultBlockState()));
        assertTrue(this.policy.shouldGuardExactReplay(Blocks.COMPARATOR.defaultBlockState()));
        assertTrue(this.policy.shouldGuardExactReplay(Blocks.REDSTONE_LAMP.defaultBlockState()));

        assertFalse(this.policy.shouldGuardExactReplay(Blocks.LEVER.defaultBlockState()));
        assertFalse(this.policy.shouldGuardExactReplay(Blocks.PISTON.defaultBlockState()));
        assertFalse(this.policy.shouldGuardExactReplay(Blocks.OBSERVER.defaultBlockState()));

        assertTrue(this.policy.shouldSuppressReplayCallbacks(Blocks.PISTON.defaultBlockState()));
        assertTrue(this.policy.shouldSuppressReplayCallbacks(Blocks.PISTON_HEAD.defaultBlockState()));
        assertTrue(this.policy.shouldSuppressReplayCallbacks(Blocks.MOVING_PISTON.defaultBlockState()));
        assertTrue(this.policy.shouldSuppressReplayCallbacks(Blocks.OBSERVER.defaultBlockState()));
    }
}
