package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.MinecraftLiveBlockWorldAccess;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
            remember(chunk, blockEntity);
        }
    }

    public void added(
            LevelChunk chunk, BlockEntity blockEntity) throws IOException {
        BlockPosition position = position(blockEntity.getBlockPos());
        BlockSnapshot after = world.read(chunk, position);
        BlockSnapshot before = baselines.put(position, after);
        if (before == null) {
            before = new BlockSnapshot(after.blockState(), Optional.empty());
        }
        recordCurrent(position, before, after);
    }

    public boolean changed(BlockEntity blockEntity) throws IOException {
        BlockPosition position = position(blockEntity.getBlockPos());
        BlockSnapshot after = world.read(blockEntity);
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

    public void rebase(SectionKey key, SectionBlob visible) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(visible, "visible");
        baselines.keySet().removeIf(position -> section(position).equals(key));
        visible.blockEntities().forEach((index, nbt) -> {
            BlockPosition position = position(key, index);
            baselines.put(position, new BlockSnapshot(
                    visible.blockStates().get(index), Optional.of(nbt)));
        });
    }

    public void rebaseSections(Set<SectionKey> sections) {
        Set<SectionKey> restored = Set.copyOf(
                Objects.requireNonNull(sections, "sections"));
        baselines.keySet().removeIf(position ->
                restored.contains(section(position)));
        baselines.putAll(world.loadedBlockEntityBaselines(restored));
    }

    public BlockSnapshot beforeMutation(
            BlockPosition position, BlockSnapshot visible) throws IOException {
        BlockSnapshot baseline = baselines.getOrDefault(
                Objects.requireNonNull(position, "position"),
                Objects.requireNonNull(visible, "visible"));
        Optional<CanonicalNbt> blockEntity =
                baseline.blockState().equals(visible.blockState())
                        ? baseline.blockEntity() : visible.blockEntity();
        BlockSnapshot before = new BlockSnapshot(visible.blockState(), blockEntity);
        world.prepare(before);
        return before;
    }

    public void removing(
            LevelChunk chunk, BlockPos blockPos) throws IOException {
        BlockPosition position = position(blockPos);
        BlockSnapshot before = world.read(chunk, position);
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

    private void remember(
            LevelChunk chunk, BlockEntity blockEntity) throws IOException {
        BlockPosition position = position(blockEntity.getBlockPos());
        baselines.put(position, world.read(chunk, position));
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
        completedBlockMutation(position, after);
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

    private static BlockPosition position(SectionKey key, int index) {
        return new BlockPosition(
                key.chunkX() * 16 + (index & 15),
                key.sectionY() * 16 + ((index >>> 8) & 15),
                key.chunkZ() * 16 + ((index >>> 4) & 15));
    }

    private static SectionKey section(BlockPosition position) {
        return new SectionKey(
                Math.floorDiv(position.x(), 16),
                Math.floorDiv(position.y(), 16),
                Math.floorDiv(position.z(), 16));
    }
}
