package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.MinecraftLiveBlockWorldAccess;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/** Keeps exact block endpoints and block-entity baselines for live Undo/Redo. */
public final class MinecraftLiveBlockTracker {
    private final LiveActionJournal journal;
    private final MinecraftLiveBlockWorldAccess world;
    private final BiConsumer<UUID, BlockPosition> mutationObserver;
    private final Map<BlockPosition, BlockSnapshot> baselines = new HashMap<>();

    public MinecraftLiveBlockTracker(
            LiveActionJournal journal,
            MinecraftLiveBlockWorldAccess world,
            BiConsumer<UUID, BlockPosition> mutationObserver) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.world = Objects.requireNonNull(world, "world");
        this.mutationObserver = Objects.requireNonNull(
                mutationObserver, "mutationObserver");
    }

    public void remember(LevelChunk chunk) throws IOException {
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            remember(blockEntity);
        }
    }

    public void added(BlockEntity blockEntity) throws IOException {
        BlockPosition position = position(blockEntity.getBlockPos());
        BlockSnapshot after = world.read(position);
        BlockSnapshot before = baselines.put(position, after);
        if (before == null) {
            before = new BlockSnapshot(after.blockState(), Optional.empty());
        }
        recordCurrent(position, before, after);
    }

    public boolean changed(BlockEntity blockEntity) throws IOException {
        BlockPosition position = position(blockEntity.getBlockPos());
        BlockSnapshot after = world.read(position);
        BlockSnapshot before = baselines.put(position, after);
        if (before != null) {
            recordCurrent(position, before, after);
        }
        return before != null && !before.equals(after);
    }

    public void completedBlockMutation(
            BlockPosition position, BlockSnapshot visible) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(visible, "visible");
        if (visible.blockEntity().isPresent()) {
            baselines.put(position, visible);
        } else {
            baselines.remove(position);
        }
    }

    public BlockSnapshot beforeMutation(
            BlockPosition position, BlockSnapshot visible) throws IOException {
        BlockSnapshot baseline = baselines.getOrDefault(
                Objects.requireNonNull(position, "position"),
                Objects.requireNonNull(visible, "visible"));
        BlockSnapshot before = new BlockSnapshot(
                visible.blockState(), baseline.blockEntity());
        world.prepare(before);
        return before;
    }

    public void removing(BlockPos blockPos) throws IOException {
        BlockPosition position = position(blockPos);
        BlockSnapshot before = world.read(position);
        baselines.put(position, new BlockSnapshot(
                before.blockState(), Optional.empty()));
        recordCurrent(position, before, baselines.get(position));
    }

    public void removed(BlockPos position) {
        baselines.remove(position(position));
    }

    public void discardChunk(int chunkX, int chunkZ) {
        baselines.keySet().removeIf(position ->
                Math.floorDiv(position.x(), 16) == chunkX
                        && Math.floorDiv(position.z(), 16) == chunkZ);
    }

    private void remember(BlockEntity blockEntity) throws IOException {
        BlockPosition position = position(blockEntity.getBlockPos());
        baselines.put(position, world.read(position));
    }

    public void record(
            UUID action,
            BlockPosition position,
            BlockSnapshot before,
            BlockSnapshot after) throws IOException {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (before.equals(after)) {
            return;
        }
        world.prepare(before);
        world.prepare(after);
        UUID effective = journal.record(action, position, before, after);
        if (!effective.equals(action)) {
            journal.mergeGroups(action, effective);
        }
        mutationObserver.accept(action, position);
    }

    private void recordCurrent(
            BlockPosition position,
            BlockSnapshot before,
            BlockSnapshot after) throws IOException {
        Optional<UUID> root = DirectLiveActionContext.current(journal);
        if (root.isEmpty()) {
            return;
        }
        UUID action = root.orElseThrow();
        record(action, position, before, after);
    }

    private static BlockPosition position(BlockPos position) {
        return new BlockPosition(position.getX(), position.getY(), position.getZ());
    }
}
