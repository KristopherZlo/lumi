package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueInput;

/** Loaded-world adapter that caches native states before tick-time live apply. */
public final class MinecraftLiveBlockWorldAccess implements LiveBlockWorldAccess {
    private static final int UPDATE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private final ServerLevel level;
    private final DimensionFreezeState freeze;
    private final MinecraftBlockStateDecoder blockDecoder;
    private final Map<BlockSnapshot, PreparedBlock> prepared = new HashMap<>();

    public MinecraftLiveBlockWorldAccess(ServerLevel level, DimensionFreezeState freeze) {
        this.level = Objects.requireNonNull(level, "level");
        this.freeze = Objects.requireNonNull(freeze, "freeze");
        blockDecoder = new MinecraftBlockStateDecoder(
                level.registryAccess().lookupOrThrow(Registries.BLOCK));
    }

    /** Decodes only changed Restore endpoints before a later live Undo/Redo. */
    public void prepareRestore(
            Map<SectionKey, SectionBlob> target,
            Map<SectionKey, SectionBlob> checkpoint) throws IOException {
        for (var entry : target.entrySet()) {
            SectionBlob after = entry.getValue();
            SectionBlob before = checkpoint.get(entry.getKey());
            for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
                var oldNbt = before.blockEntities().get(index);
                var newNbt = after.blockEntities().get(index);
                String oldState = before.blockStates().get(index);
                String newState = after.blockStates().get(index);
                if (oldState.equals(newState) && Objects.equals(oldNbt, newNbt)) continue;
                prepare(new BlockSnapshot(oldState, Optional.ofNullable(oldNbt)));
                prepare(new BlockSnapshot(newState, Optional.ofNullable(newNbt)));
            }
        }
    }

    @Override
    public void requirePrepared(BlockSnapshot snapshot) throws IOException {
        if (!prepared.containsKey(Objects.requireNonNull(snapshot, "snapshot"))) {
            throw new IOException("Live block state was not prepared before apply");
        }
    }

    @Override
    public BlockSnapshot read(BlockPosition position) throws IOException {
        BlockPos blockPos = minecraft(position);
        return read(requireChunk(blockPos), blockPos);
    }

    public BlockSnapshot read(
            LevelChunk chunk, BlockPosition position) throws IOException {
        return read(
                Objects.requireNonNull(chunk, "chunk"),
                minecraft(Objects.requireNonNull(position, "position")));
    }

    public BlockSnapshot read(BlockEntity blockEntity) throws IOException {
        Objects.requireNonNull(blockEntity, "blockEntity");
        return snapshot(blockEntity.getBlockState(), blockEntity);
    }

    public boolean isLoaded(SectionKey section) {
        Objects.requireNonNull(section, "section");
        return level.getChunkSource().getChunkNow(
                section.chunkX(), section.chunkZ()) != null;
    }

    private BlockSnapshot read(
            LevelChunk chunk, BlockPos blockPos) throws IOException {
        return snapshot(
                chunk.getBlockState(blockPos), chunk.getBlockEntity(blockPos));
    }

    private BlockSnapshot snapshot(
            BlockState state, BlockEntity blockEntity) throws IOException {
        Optional<CompoundTag> nbt = blockEntity == null
                || !blockEntity.getType().isValid(state)
                ? Optional.empty() : Optional.of(canonicalTag(blockEntity));
        Optional<CanonicalNbt> canonicalNbt = nbt.isEmpty()
                ? Optional.empty() : Optional.of(MinecraftNbtCodec.encode(nbt.orElseThrow()));
        BlockSnapshot snapshot = new BlockSnapshot(
                BlockStateParser.serialize(state), canonicalNbt);
        prepared.put(snapshot, new PreparedBlock(state, nbt.map(CompoundTag::copy)));
        return snapshot;
    }

    @Override
    public void write(BlockPosition position, BlockSnapshot snapshot) throws IOException {
        requirePrepared(snapshot);
        PreparedBlock target = prepared.get(snapshot);
        BlockPos blockPos = minecraft(position);
        LevelChunk chunk = requireChunk(blockPos);
        freeze.runAuthorized(() -> {
            level.setBlock(blockPos, target.state, UPDATE_FLAGS);
            applyBlockEntity(chunk, blockPos, target.nbt);
        });
    }

    @Override
    public CompletableFuture<Void> finishLighting(Set<BlockPosition> positions) {
        return CompletableFuture.allOf(positions.stream()
                .mapToLong(position -> net.minecraft.world.level.ChunkPos.asLong(
                        position.x() >> 4, position.z() >> 4))
                .distinct()
                .mapToObj(chunk -> level.getChunkSource().getLightEngine()
                        .waitForPendingTasks(
                                net.minecraft.world.level.ChunkPos.getX(chunk),
                                net.minecraft.world.level.ChunkPos.getZ(chunk)))
                .toArray(CompletableFuture[]::new));
    }

    public void clear() {
        prepared.clear();
    }

    public void prepare(BlockSnapshot snapshot) throws IOException {
        if (prepared.containsKey(snapshot)) {
            return;
        }
        BlockState state = blockDecoder.decodeState(snapshot.blockState());
        Optional<CompoundTag> nbt = snapshot.blockEntity().isEmpty()
                ? Optional.empty()
                : Optional.of(MinecraftNbtCodec.decode(
                        snapshot.blockEntity().orElseThrow()));
        if (nbt.isPresent()) {
            blockDecoder.validateBlockEntityState(state, nbt.orElseThrow());
        }
        prepared.put(snapshot, new PreparedBlock(state, nbt));
    }

    private void applyBlockEntity(LevelChunk chunk, BlockPos position, Optional<CompoundTag> nbt) {
        if (nbt.isEmpty()) {
            chunk.removeBlockEntity(position);
            return;
        }
        CompoundTag full = nbt.orElseThrow().copy();
        full.putInt("x", position.getX());
        full.putInt("y", position.getY());
        full.putInt("z", position.getZ());
        BlockEntity existing = chunk.getBlockEntity(position);
        if (existing == null) {
            BlockEntity replacement = BlockEntity.loadStatic(
                    position, chunk.getBlockState(position), full, level.registryAccess());
            if (replacement == null) {
                throw new IllegalStateException("Cannot create live block entity at " + position);
            }
            chunk.setBlockEntity(replacement);
        } else {
            existing.loadWithComponents(TagValueInput.create(
                    ProblemReporter.DISCARDING, level.registryAccess(), full));
            existing.setChanged();
        }
    }

    private CompoundTag canonicalTag(BlockEntity blockEntity) {
        CompoundTag tag = blockEntity.saveWithFullMetadata(level.registryAccess());
        tag.remove("x");
        tag.remove("y");
        tag.remove("z");
        return tag;
    }

    private LevelChunk requireChunk(BlockPos position) throws IOException {
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                position.getX() >> 4, position.getZ() >> 4);
        if (chunk == null) {
            throw new IOException("Live action chunk is not loaded: " + position);
        }
        return chunk;
    }

    private static BlockPos minecraft(BlockPosition position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private record PreparedBlock(BlockState state, Optional<CompoundTag> nbt) { }
}
