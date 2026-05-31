package io.github.luma.minecraft.world;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Selects replay states that may drift because of delayed vanilla callbacks
 * and are safe to hold briefly after history replay.
 */
final class ExactReplayGuardBlockPolicy {

    private final MechanismStatePolicy mechanismStatePolicy = new MechanismStatePolicy();

    boolean shouldGuard(BlockState state) {
        return this.isReplaySensitiveExplosive(state)
                || this.mechanismStatePolicy.shouldGuardExactReplay(state);
    }

    boolean shouldSuppressCallbacks(BlockState state) {
        return this.isReplaySensitiveExplosive(state)
                || this.mechanismStatePolicy.shouldSuppressReplayCallbacks(state);
    }

    private boolean isReplaySensitiveExplosive(BlockState state) {
        return state != null && state.is(Blocks.TNT);
    }
}
