package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.world.MechanismStatePolicy;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Opens capture source frames for redstone and mechanism fallout that Minecraft
 * runs from neighbor updates or scheduled block ticks.
 */
public final class BlockUpdateCaptureContext {

    private static final BlockUpdateCaptureContext INSTANCE = new BlockUpdateCaptureContext();
    private final MechanismStatePolicy mechanismStatePolicy = new MechanismStatePolicy();

    public static BlockUpdateCaptureContext getInstance() {
        return INSTANCE;
    }

    private BlockUpdateCaptureContext() {
    }

    public WorldMutationContext.SourceFrame pushFor(BlockState state) {
        if (!this.shouldScope(state)) {
            return null;
        }
        WorldMutationSource currentSource = WorldMutationContext.currentSource();
        if (WorldMutationContext.captureSuppressed()
                || currentSource == WorldMutationSource.RESTORE
                || currentSource == WorldMutationSource.FLUID
                || currentSource == WorldMutationSource.PISTON
                || currentSource == WorldMutationSource.BLOCK_UPDATE) {
            return null;
        }
        return WorldMutationContext.pushSource(WorldMutationSource.BLOCK_UPDATE);
    }

    boolean shouldScope(BlockState state) {
        return this.mechanismStatePolicy.shouldScopeBlockUpdate(state);
    }
}
