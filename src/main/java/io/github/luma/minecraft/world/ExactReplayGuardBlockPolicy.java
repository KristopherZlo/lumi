package io.github.luma.minecraft.world;

import java.util.Locale;
import java.util.Set;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Selects replay states that may drift because of delayed vanilla redstone
 * callbacks and are safe to hold briefly after history replay.
 */
final class ExactReplayGuardBlockPolicy {

    private static final Set<String> VOLATILE_PROPERTY_NAMES = Set.of(
            "delay",
            "east",
            "extended",
            "lit",
            "locked",
            "mode",
            "north",
            "power",
            "powered",
            "south",
            "triggered",
            "west"
    );

    boolean shouldGuard(BlockState state) {
        if (state == null || state.isAir() || this.isPlayerInputControl(state)) {
            return false;
        }
        for (Property<?> property : state.getProperties()) {
            if (VOLATILE_PROPERTY_NAMES.contains(property.getName().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlayerInputControl(BlockState state) {
        Block block = state.getBlock();
        return block instanceof LeverBlock
                || block instanceof ButtonBlock
                || block instanceof BasePressurePlateBlock
                || block instanceof TripWireBlock
                || block instanceof TripWireHookBlock;
    }
}
