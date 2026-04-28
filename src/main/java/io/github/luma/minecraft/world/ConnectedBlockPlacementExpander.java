package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Completes paired block placements so restore replay does not leave one half
 * of a bed, door, or tall plant behind when only one persisted cell changed.
 */
public final class ConnectedBlockPlacementExpander {

    private static final Comparator<PreparedBlockPlacement> APPLY_ORDER = Comparator
            .comparingInt(ConnectedBlockPlacementExpander::pairApplyPriority);

    public List<PreparedBlockPlacement> expandTargets(List<PreparedBlockPlacement> placements) {
        LinkedHashMap<Long, PreparedBlockPlacement> explicitPlacements = this.indexPlacements(placements);
        LinkedHashMap<Long, PreparedBlockPlacement> expandedPlacements = new LinkedHashMap<>(explicitPlacements);
        for (PreparedBlockPlacement placement : explicitPlacements.values()) {
            this.addIfAbsent(expandedPlacements, explicitPlacements, this.targetCompanion(placement));
        }
        return ordered(expandedPlacements.values().stream().toList());
    }

    public List<PreparedBlockPlacement> expandChanges(List<ChangePlacement> changes) {
        LinkedHashMap<Long, PreparedBlockPlacement> explicitPlacements = new LinkedHashMap<>();
        for (ChangePlacement change : changes == null ? List.<ChangePlacement>of() : changes) {
            if (change == null || change.placement() == null) {
                continue;
            }
            explicitPlacements.put(packed(change.placement().pos()), change.placement());
        }

        LinkedHashMap<Long, PreparedBlockPlacement> expandedPlacements = new LinkedHashMap<>(explicitPlacements);
        for (ChangePlacement change : changes == null ? List.<ChangePlacement>of() : changes) {
            if (change == null || change.placement() == null) {
                continue;
            }
            PreparedBlockPlacement targetCompanion = this.targetCompanion(change.placement());
            if (targetCompanion != null) {
                this.addIfAbsent(expandedPlacements, explicitPlacements, targetCompanion);
                continue;
            }
            this.addIfAbsent(
                    expandedPlacements,
                    explicitPlacements,
                    this.sourceRemovalCompanion(change.placement().pos(), change.sourceState())
            );
        }
        return ordered(expandedPlacements.values().stream().toList());
    }

    public Map<ChunkPoint, List<PreparedBlockPlacement>> groupByChunk(List<PreparedBlockPlacement> placements) {
        LinkedHashMap<ChunkPoint, List<PreparedBlockPlacement>> grouped = new LinkedHashMap<>();
        for (PreparedBlockPlacement placement : placements == null ? List.<PreparedBlockPlacement>of() : placements) {
            grouped.computeIfAbsent(ChunkPoint.from(placement.pos()), ignored -> new ArrayList<>()).add(placement);
        }
        return grouped;
    }

    public static List<PreparedBlockPlacement> ordered(List<PreparedBlockPlacement> placements) {
        List<PreparedBlockPlacement> ordered = new ArrayList<>(placements == null ? List.of() : placements);
        ordered.sort(APPLY_ORDER);
        return List.copyOf(ordered);
    }

    private LinkedHashMap<Long, PreparedBlockPlacement> indexPlacements(List<PreparedBlockPlacement> placements) {
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
            LinkedHashMap<Long, PreparedBlockPlacement> expandedPlacements,
            LinkedHashMap<Long, PreparedBlockPlacement> explicitPlacements,
            PreparedBlockPlacement placement
    ) {
        if (placement == null || placement.pos() == null) {
            return;
        }
        long key = packed(placement.pos());
        if (!explicitPlacements.containsKey(key)) {
            expandedPlacements.putIfAbsent(key, placement);
        }
    }

    private PreparedBlockPlacement targetCompanion(PreparedBlockPlacement placement) {
        if (placement == null || placement.state() == null || placement.state().isAir()) {
            return null;
        }

        CompanionPlacement companion = this.connectedCompanion(placement.pos(), placement.state());
        if (companion == null) {
            return null;
        }
        return new PreparedBlockPlacement(companion.pos(), companion.state(), null);
    }

    private PreparedBlockPlacement sourceRemovalCompanion(BlockPos pos, BlockState sourceState) {
        if (pos == null || sourceState == null || sourceState.isAir()) {
            return null;
        }

        CompanionPlacement companion = this.connectedCompanion(pos, sourceState);
        if (companion == null) {
            return null;
        }
        return new PreparedBlockPlacement(companion.pos(), Blocks.AIR.defaultBlockState(), null);
    }

    private CompanionPlacement connectedCompanion(BlockPos pos, BlockState state) {
        if (state == null) {
            return null;
        }
        if (state.getBlock() instanceof BedBlock && state.hasProperty(BedBlock.PART) && state.hasProperty(BedBlock.FACING)) {
            BedPart part = state.getValue(BedBlock.PART);
            Direction facing = state.getValue(BedBlock.FACING);
            BlockPos companionPos = part == BedPart.FOOT ? pos.relative(facing) : pos.relative(facing.getOpposite());
            return new CompanionPlacement(
                    companionPos,
                    state.setValue(BedBlock.PART, part == BedPart.FOOT ? BedPart.HEAD : BedPart.FOOT)
            );
        }
        if (state.getBlock() instanceof DoorBlock && state.hasProperty(DoorBlock.HALF)) {
            DoubleBlockHalf half = state.getValue(DoorBlock.HALF);
            return this.verticalCompanion(pos, state, DoorBlock.HALF, half);
        }
        if (state.getBlock() instanceof DoublePlantBlock && state.hasProperty(DoublePlantBlock.HALF)) {
            DoubleBlockHalf half = state.getValue(DoublePlantBlock.HALF);
            return this.verticalCompanion(pos, state, DoublePlantBlock.HALF, half);
        }
        return null;
    }

    private CompanionPlacement verticalCompanion(
            BlockPos pos,
            BlockState state,
            net.minecraft.world.level.block.state.properties.EnumProperty<DoubleBlockHalf> property,
            DoubleBlockHalf half
    ) {
        BlockPos companionPos = half == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        return new CompanionPlacement(
                companionPos,
                state.setValue(property, half == DoubleBlockHalf.LOWER ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER)
        );
    }

    private static int pairApplyPriority(PreparedBlockPlacement placement) {
        if (placement == null || placement.state() == null) {
            return 0;
        }
        BlockState state = placement.state();
        if (state.getBlock() instanceof BedBlock && state.hasProperty(BedBlock.PART)) {
            return state.getValue(BedBlock.PART) == BedPart.HEAD ? 1 : 0;
        }
        if (state.getBlock() instanceof DoorBlock && state.hasProperty(DoorBlock.HALF)) {
            return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? 1 : 0;
        }
        if (state.getBlock() instanceof DoublePlantBlock && state.hasProperty(DoublePlantBlock.HALF)) {
            return state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER ? 1 : 0;
        }
        return 0;
    }

    private static long packed(BlockPos pos) {
        return BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
    }

    public record ChangePlacement(PreparedBlockPlacement placement, BlockState sourceState) {
    }

    private record CompanionPlacement(BlockPos pos, BlockState state) {
    }
}
