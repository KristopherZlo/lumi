package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Captures mutable block-entity payload changes that do not replace the block state.
 */
public final class BlockEntityMutationSnapshotRegistry {

    private static final BlockEntityMutationSnapshotRegistry INSTANCE = new BlockEntityMutationSnapshotRegistry();

    private final Map<BlockEntity, Snapshot> snapshots = new WeakHashMap<>();

    private BlockEntityMutationSnapshotRegistry() {
    }

    public static BlockEntityMutationSnapshotRegistry getInstance() {
        return INSTANCE;
    }

    public void captureBeforePotentialMutation(BlockEntity blockEntity) {
        if (blockEntity == null || !this.canCaptureCurrentSource()) {
            return;
        }
        Level level = blockEntity.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        synchronized (this.snapshots) {
            this.snapshots.computeIfAbsent(blockEntity, ignored -> this.snapshot(serverLevel, blockEntity));
        }
    }

    public void recordIfChanged(BlockEntity blockEntity) {
        if (blockEntity == null || !this.canCaptureCurrentSource()) {
            return;
        }
        Level level = blockEntity.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            this.remove(blockEntity);
            return;
        }

        Snapshot snapshot = this.remove(blockEntity);
        if (snapshot == null) {
            return;
        }

        CompoundTag currentTag = blockEntity.saveWithFullMetadata(serverLevel.registryAccess());
        if (Objects.equals(snapshot.tag(), currentTag)) {
            return;
        }

        HistoryCaptureManager.getInstance().recordBlockChange(
                serverLevel,
                snapshot.pos(),
                snapshot.state(),
                blockEntity.getBlockState(),
                snapshot.tag(),
                currentTag
        );
    }

    private Snapshot remove(BlockEntity blockEntity) {
        synchronized (this.snapshots) {
            return this.snapshots.remove(blockEntity);
        }
    }

    private boolean canCaptureCurrentSource() {
        WorldMutationSource source = WorldMutationContext.currentSource();
        return HistoryCaptureManager.shouldCaptureMutation(source);
    }

    private Snapshot snapshot(ServerLevel level, BlockEntity blockEntity) {
        return new Snapshot(
                blockEntity.getBlockPos().immutable(),
                blockEntity.getBlockState(),
                blockEntity.saveWithFullMetadata(level.registryAccess())
        );
    }

    private record Snapshot(BlockPos pos, BlockState state, CompoundTag tag) {
    }
}
