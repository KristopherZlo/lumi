package io.github.luma.minecraft.world;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Selects replay states that may drift because of delayed vanilla redstone
 * callbacks and are safe to hold briefly after history replay.
 */
final class ExactReplayGuardBlockPolicy {

    private final MechanismStatePolicy mechanismStatePolicy = new MechanismStatePolicy();

    boolean shouldGuard(BlockState state) {
        return this.mechanismStatePolicy.shouldGuardExactReplay(state);
    }

    boolean shouldSuppressCallbacks(BlockState state) {
        return this.mechanismStatePolicy.shouldSuppressReplayCallbacks(state);
    }
}
