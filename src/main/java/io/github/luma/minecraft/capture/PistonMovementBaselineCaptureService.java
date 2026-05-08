package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Captures the affected piston movement envelope before vanilla replaces moved
 * blocks with transient moving-piston block entities.
 */
public final class PistonMovementBaselineCaptureService {

    private static final PistonMovementBaselineCaptureService INSTANCE = new PistonMovementBaselineCaptureService();

    public static PistonMovementBaselineCaptureService getInstance() {
        return INSTANCE;
    }

    private PistonMovementBaselineCaptureService() {
    }

    public void captureBeforePistonEvent(
            ServerLevel level,
            BlockPos pistonPos,
            BlockState pistonState,
            int eventType
    ) {
        if (level == null
                || pistonPos == null
                || pistonState == null
                || WorldMutationContext.currentSource() != WorldMutationSource.PISTON
                || !pistonState.hasProperty(PistonBaseBlock.FACING)) {
            return;
        }

        Direction facing = pistonState.getValue(PistonBaseBlock.FACING);
        boolean extending = this.isExtendingEvent(eventType);
        for (BlockPos pos : this.affectedPositions(level, pistonPos, facing, extending)) {
            BlockState oldState = level.getBlockState(pos);
            HistoryCaptureManager.getInstance().capturePreMutationBaseline(
                    level,
                    pos,
                    oldState,
                    this.blockEntityTag(level, pos, oldState)
            );
        }
    }

    Set<BlockPos> affectedPositions(Level level, BlockPos pistonPos, Direction facing, boolean extending) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        if (level == null || pistonPos == null || facing == null) {
            return positions;
        }

        positions.add(pistonPos.immutable());
        positions.add(pistonPos.relative(facing).immutable());
        PistonStructureResolver resolver = new PistonStructureResolver(level, pistonPos, facing, extending);
        if (!resolver.resolve()) {
            return positions;
        }

        return this.affectedPositions(
                pistonPos,
                facing,
                extending,
                resolver.getToPush(),
                resolver.getToDestroy()
        );
    }

    Set<BlockPos> affectedPositions(
            BlockPos pistonPos,
            Direction facing,
            boolean extending,
            List<BlockPos> movedPositions,
            List<BlockPos> destroyedPositions
    ) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        if (pistonPos == null || facing == null) {
            return positions;
        }
        positions.add(pistonPos.immutable());
        positions.add(pistonPos.relative(facing).immutable());
        Direction motionDirection = extending ? facing : facing.getOpposite();
        this.addMovedPositions(positions, movedPositions, motionDirection);
        this.addPositions(positions, destroyedPositions);
        return positions;
    }

    private void addMovedPositions(Set<BlockPos> positions, List<BlockPos> movedPositions, Direction motionDirection) {
        for (BlockPos movedPos : movedPositions == null ? List.<BlockPos>of() : movedPositions) {
            if (movedPos == null) {
                continue;
            }
            positions.add(movedPos.immutable());
            positions.add(movedPos.relative(motionDirection).immutable());
        }
    }

    private void addPositions(Set<BlockPos> positions, List<BlockPos> sourcePositions) {
        for (BlockPos pos : sourcePositions == null ? List.<BlockPos>of() : sourcePositions) {
            if (pos != null) {
                positions.add(pos.immutable());
            }
        }
    }

    private boolean isExtendingEvent(int eventType) {
        return eventType == 0;
    }

    private CompoundTag blockEntityTag(ServerLevel level, BlockPos pos, BlockState state) {
        if (state == null || !state.hasBlockEntity()) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
    }
}
