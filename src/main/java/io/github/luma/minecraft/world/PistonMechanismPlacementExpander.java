package io.github.luma.minecraft.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;

/**
 * Completes settled piston base/head placements for direct history replay.
 */
public final class PistonMechanismPlacementExpander {

    private static final Comparator<PreparedBlockPlacement> APPLY_ORDER = Comparator
            .comparingInt(PistonMechanismPlacementExpander::applyPriority);

    public List<PreparedBlockPlacement> expandTargets(Collection<PreparedBlockPlacement> placements) {
        LinkedHashMap<Long, PreparedBlockPlacement> explicit = this.indexPlacements(placements);
        LinkedHashMap<Long, PreparedBlockPlacement> expanded = new LinkedHashMap<>(explicit);
        for (PreparedBlockPlacement placement : explicit.values()) {
            this.addHeadCompanion(expanded, explicit, this.targetHeadCompanion(placement));
        }
        return ordered(expanded.values());
    }

    public List<PreparedBlockPlacement> expandChanges(Collection<ChangePlacement> changes) {
        LinkedHashMap<Long, PreparedBlockPlacement> explicit = new LinkedHashMap<>();
        for (ChangePlacement change : changes == null ? List.<ChangePlacement>of() : changes) {
            if (change != null && change.placement() != null && change.placement().pos() != null) {
                explicit.put(packed(change.placement().pos()), change.placement());
            }
        }

        LinkedHashMap<Long, PreparedBlockPlacement> expanded = new LinkedHashMap<>(explicit);
        for (ChangePlacement change : changes == null ? List.<ChangePlacement>of() : changes) {
            if (change == null || change.placement() == null) {
                continue;
            }
            PreparedBlockPlacement placement = change.placement();
            this.addHeadCompanion(expanded, explicit, this.targetHeadCompanion(placement));
            this.addIfAbsent(expanded, explicit, this.sourceHeadRemovalCompanion(
                    placement.pos(),
                    change.sourceState(),
                    placement.state()
            ));
        }
        return ordered(expanded.values());
    }

    public static List<PreparedBlockPlacement> ordered(Collection<PreparedBlockPlacement> placements) {
        List<PreparedBlockPlacement> ordered = new ArrayList<>(placements == null ? List.of() : placements);
        ordered.sort(APPLY_ORDER);
        return List.copyOf(ordered);
    }

    public boolean requiresCompanion(BlockState state) {
        return this.isExtendedBase(state);
    }

    private LinkedHashMap<Long, PreparedBlockPlacement> indexPlacements(Collection<PreparedBlockPlacement> placements) {
        LinkedHashMap<Long, PreparedBlockPlacement> indexed = new LinkedHashMap<>();
        for (PreparedBlockPlacement placement : placements == null ? List.<PreparedBlockPlacement>of() : placements) {
            if (placement == null || placement.pos() == null) {
                continue;
            }
            indexed.put(packed(placement.pos()), placement);
        }
        return indexed;
    }

    private void addIfAbsent(
            LinkedHashMap<Long, PreparedBlockPlacement> expanded,
            LinkedHashMap<Long, PreparedBlockPlacement> explicit,
            PreparedBlockPlacement placement
    ) {
        if (placement == null || placement.pos() == null) {
            return;
        }
        long key = packed(placement.pos());
        if (!explicit.containsKey(key)) {
            expanded.putIfAbsent(key, placement);
        }
    }

    private void addHeadCompanion(
            LinkedHashMap<Long, PreparedBlockPlacement> expanded,
            LinkedHashMap<Long, PreparedBlockPlacement> explicit,
            PreparedBlockPlacement placement
    ) {
        if (placement == null || placement.pos() == null) {
            return;
        }
        long key = packed(placement.pos());
        PreparedBlockPlacement explicitPlacement = explicit.get(key);
        if (explicitPlacement == null || this.isAir(explicitPlacement.state())) {
            expanded.put(key, placement);
        }
    }

    private PreparedBlockPlacement targetHeadCompanion(PreparedBlockPlacement placement) {
        if (placement == null || placement.pos() == null || !this.isExtendedBase(placement.state())) {
            return null;
        }
        Direction facing = placement.state().getValue(PistonBaseBlock.FACING);
        PistonType type = placement.state().is(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT;
        BlockState head = Blocks.PISTON_HEAD.defaultBlockState()
                .setValue(PistonHeadBlock.FACING, facing)
                .setValue(PistonHeadBlock.TYPE, type);
        return new PreparedBlockPlacement(placement.pos().relative(facing), head, null);
    }

    private PreparedBlockPlacement sourceHeadRemovalCompanion(BlockPos pos, BlockState sourceState, BlockState targetState) {
        if (pos == null || !this.isExtendedBase(sourceState) || this.sameExtendedBase(sourceState, targetState)) {
            return null;
        }
        return new PreparedBlockPlacement(
                pos.relative(sourceState.getValue(PistonBaseBlock.FACING)),
                Blocks.AIR.defaultBlockState(),
                null
        );
    }

    private boolean sameExtendedBase(BlockState sourceState, BlockState targetState) {
        return this.isExtendedBase(sourceState)
                && this.isExtendedBase(targetState)
                && sourceState.getBlock() == targetState.getBlock()
                && sourceState.getValue(PistonBaseBlock.FACING) == targetState.getValue(PistonBaseBlock.FACING);
    }

    private boolean isExtendedBase(BlockState state) {
        return state != null
                && (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON))
                && state.hasProperty(PistonBaseBlock.EXTENDED)
                && state.getValue(PistonBaseBlock.EXTENDED);
    }

    private boolean isAir(BlockState state) {
        return state == null || state.isAir();
    }

    private static int applyPriority(PreparedBlockPlacement placement) {
        if (placement == null || placement.state() == null) {
            return 0;
        }
        BlockState state = placement.state();
        if (state.isAir()) {
            return 0;
        }
        if (state.is(Blocks.PISTON_HEAD)) {
            return 2;
        }
        if (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON)) {
            return 3;
        }
        return 1;
    }

    private static long packed(BlockPos pos) {
        return BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
    }

    public record ChangePlacement(PreparedBlockPlacement placement, BlockState sourceState) {
    }
}
