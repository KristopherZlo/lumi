package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.Set;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Opens capture source frames for redstone and mechanism fallout that Minecraft
 * runs from neighbor updates or scheduled block ticks.
 */
public final class BlockUpdateCaptureContext {

    private static final BlockUpdateCaptureContext INSTANCE = new BlockUpdateCaptureContext();
    private static final Set<String> MECHANISM_PROPERTY_NAMES = Set.of(
            "attached",
            "enabled",
            "extended",
            "in_wall",
            "lit",
            "locked",
            "open",
            "power",
            "powered",
            "triggered"
    );

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
                || currentSource == WorldMutationSource.PISTON
                || currentSource == WorldMutationSource.BLOCK_UPDATE) {
            return null;
        }
        return WorldMutationContext.pushSource(WorldMutationSource.BLOCK_UPDATE);
    }

    boolean shouldScope(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON)) {
            return true;
        }
        for (Property<?> property : state.getProperties()) {
            if (MECHANISM_PROPERTY_NAMES.contains(property.getName())) {
                return true;
            }
        }
        return false;
    }
}
