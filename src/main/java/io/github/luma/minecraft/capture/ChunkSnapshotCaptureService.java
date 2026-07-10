package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.minecraft.world.PersistentBlockStatePolicy;
import io.github.luma.storage.repository.SnapshotWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.phys.AABB;

/**
 * Captures already-loaded chunk state into an immutable compact payload.
 *
 * <p>The server thread copies section containers and block entity tags once,
 * then background persistence and later stabilization can reuse that payload
 * without touching the live world again.
 */
public final class ChunkSnapshotCaptureService {

    private static final Strategy<BlockState> BLOCK_STATE_STRATEGY = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
    private static final String AIR_BLOCK_ID = "minecraft:air";
    private static final double PLACED_ENTITY_SEARCH_MARGIN = 16.0D;

    private final PersistentBlockStatePolicy blockStatePolicy = new PersistentBlockStatePolicy();
    private final EntitySnapshotService entitySnapshotService = new EntitySnapshotService();
    private final PlacedEntityHistoryPolicy placedEntityHistoryPolicy = new PlacedEntityHistoryPolicy();

    public Optional<ChunkSnapshotPayload> captureLoadedChunk(ServerLevel level, ChunkPoint chunk) {
        return this.captureLoadedChunk(level, chunk, null, null, null);
    }

    LoadedBlockStateCapture captureLoadedStableBlockState(ServerLevel level, ChunkPoint chunk) {
        if (level == null || chunk == null) {
            return LoadedBlockStateCapture.missing();
        }
        LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
        if (levelChunk == null) {
            return LoadedBlockStateCapture.missing();
        }
        ChunkSnapshotPayload payload = this.capture(
                level,
                levelChunk,
                List.of(),
                EntitySnapshotOverride.none(),
                true,
                false
        );
        return payload == null ? LoadedBlockStateCapture.transientCapture() : LoadedBlockStateCapture.captured(payload);
    }

    boolean containsTransientBlockState(ServerLevel level, ChunkPoint chunk) {
        if (level == null || chunk == null) {
            return false;
        }
        LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
        return levelChunk != null && this.containsTransientBlockState(levelChunk);
    }

    public Optional<ChunkSnapshotPayload> captureChunk(ServerLevel level, ChunkPoint chunk) {
        if (level == null || chunk == null) {
            return Optional.empty();
        }
        LevelChunk levelChunk = level.getChunk(chunk.x(), chunk.z());
        return Optional.of(this.capture(level, levelChunk, List.of(), EntitySnapshotOverride.none(), false, true));
    }

    public Optional<ChunkSnapshotPayload> captureEntityCheckpointChunk(ServerLevel level, ChunkPoint chunk) {
        if (level == null || chunk == null) {
            return Optional.empty();
        }
        LevelChunk levelChunk = level.getChunk(chunk.x(), chunk.z());
        return Optional.of(new ChunkSnapshotPayload(
                chunk.x(),
                chunk.z(),
                level.getMinY(),
                level.getMaxY(),
                List.of(),
                Map.of(),
                this.captureEntities(level, levelChunk, EntitySnapshotOverride.none())
        ));
    }

    public Optional<ChunkSnapshotPayload> captureLoadedChunk(
            ServerLevel level,
            ChunkPoint chunk,
            BlockPos overridePos,
            BlockState overrideState,
            CompoundTag overrideBlockEntity
    ) {
        return this.captureLoadedChunk(
                level,
                chunk,
                blockStateOverrides(overridePos, overrideState, overrideBlockEntity),
                EntitySnapshotOverride.none()
        );
    }

    Optional<ChunkSnapshotPayload> captureLoadedChunk(
            ServerLevel level,
            ChunkPoint chunk,
            List<BlockStateOverride> blockStateOverrides
    ) {
        return this.captureLoadedChunk(level, chunk, blockStateOverrides, EntitySnapshotOverride.none());
    }

    public Optional<ChunkSnapshotPayload> captureLoadedChunk(
            ServerLevel level,
            ChunkPoint chunk,
            BlockPos overridePos,
            BlockState overrideState,
            CompoundTag overrideBlockEntity,
            EntityPayload oldEntityPayload,
            EntityPayload newEntityPayload
    ) {
        return this.captureLoadedChunk(
                level,
                chunk,
                blockStateOverrides(overridePos, overrideState, overrideBlockEntity),
                new EntitySnapshotOverride(oldEntityPayload, newEntityPayload)
        );
    }

    boolean containsTransientBlockState(LevelChunk chunk) {
        if (chunk == null) {
            return false;
        }
        for (LevelChunkSection section : chunk.getSections()) {
            if (this.containsTransientBlockState(section)) {
                return true;
            }
        }
        return false;
    }

    boolean containsTransientBlockState(LevelChunkSection section) {
        if (section == null) {
            return false;
        }
        PalettedContainerRO.PackedData<BlockState> packedData = section.getStates().pack(BLOCK_STATE_STRATEGY);
        for (BlockState blockState : packedData.paletteEntries()) {
            if (this.blockStatePolicy.isTransientPistonState(blockState)) {
                return true;
            }
        }
        return false;
    }

    private Optional<ChunkSnapshotPayload> captureLoadedChunk(
            ServerLevel level,
            ChunkPoint chunk,
            List<BlockStateOverride> blockStateOverrides,
            EntitySnapshotOverride entityOverride
    ) {
        if (level == null || chunk == null) {
            return Optional.empty();
        }
        LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
        if (levelChunk == null) {
            return Optional.empty();
        }
        return Optional.of(this.capture(level, levelChunk, blockStateOverrides, entityOverride, false, true));
    }

    private ChunkSnapshotPayload capture(
            ServerLevel level,
            LevelChunk chunk,
            List<BlockStateOverride> blockStateOverrides,
            EntitySnapshotOverride entityOverride,
            boolean rejectTransientState,
            boolean includeEntities
    ) {
        Map<Integer, List<NormalizedBlockStateOverride>> overridesBySection =
                this.normalizedOverridesBySection(blockStateOverrides);
        List<ChunkSectionSnapshotPayload> sections = new ArrayList<>();

        LevelChunkSection[] chunkSections = chunk.getSections();
        for (int index = 0; index < chunkSections.length; index++) {
            LevelChunkSection section = chunkSections[index];
            if (section == null) {
                continue;
            }
            int sectionY = level.getSectionYFromSectionIndex(index);
            LevelChunkSection sectionCopy = section.copy();
            for (NormalizedBlockStateOverride override : overridesBySection.getOrDefault(sectionY, List.of())) {
                sectionCopy.setBlockState(
                        override.pos().getX() & 15,
                        override.pos().getY() & 15,
                        override.pos().getZ() & 15,
                        override.state().state()
                );
            }
            CapturedSection capturedSection = this.captureSection(sectionCopy, sectionY);
            if (rejectTransientState && capturedSection.transientState()) {
                return null;
            }
            if (capturedSection.payload() != null) {
                sections.add(capturedSection.payload());
            }
        }

        Map<Integer, CompoundTag> blockEntities = this.captureBlockEntities(level, chunk);
        for (List<NormalizedBlockStateOverride> sectionOverrides : overridesBySection.values()) {
            for (NormalizedBlockStateOverride override : sectionOverrides) {
                int packedIndex = SnapshotWriter.packVerticalIndex(
                        override.pos().getY() - level.getMinY(),
                        override.pos().getX() & 15,
                        override.pos().getZ() & 15
                );
                if (override.state().blockEntityTag() == null || override.state().state().isAir()) {
                    blockEntities.remove(packedIndex);
                } else {
                    blockEntities.put(packedIndex, override.state().blockEntityTag());
                }
            }
        }

        return new ChunkSnapshotPayload(
                chunk.getPos().x,
                chunk.getPos().z,
                level.getMinY(),
                level.getMaxY(),
                sections,
                blockEntities,
                includeEntities ? this.captureEntities(level, chunk, entityOverride) : List.of()
        );
    }

    private static List<BlockStateOverride> blockStateOverrides(
            BlockPos overridePos,
            BlockState overrideState,
            CompoundTag overrideBlockEntity
    ) {
        return overridePos == null || overrideState == null
                ? List.of()
                : List.of(new BlockStateOverride(overridePos, overrideState, overrideBlockEntity));
    }

    private Map<Integer, List<NormalizedBlockStateOverride>> normalizedOverridesBySection(
            List<BlockStateOverride> overrides
    ) {
        LinkedHashMap<Integer, List<NormalizedBlockStateOverride>> bySection = new LinkedHashMap<>();
        for (BlockStateOverride override : overrides == null ? List.<BlockStateOverride>of() : overrides) {
            if (override == null || override.pos() == null || override.state() == null) {
                continue;
            }
            BlockPos pos = override.pos().immutable();
            bySection.computeIfAbsent(pos.getY() >> 4, ignored -> new ArrayList<>())
                    .add(new NormalizedBlockStateOverride(
                            pos,
                            this.blockStatePolicy.normalize(override.state(), override.blockEntityTag())
                    ));
        }
        return bySection;
    }

    private CapturedSection captureSection(LevelChunkSection section, int sectionY) {
        PalettedContainerRO.PackedData<BlockState> packedData = section.getStates().pack(BLOCK_STATE_STRATEGY);
        List<CompoundTag> palette = new ArrayList<>(packedData.paletteEntries().size());
        boolean nonAir = false;
        boolean transientState = false;
        for (BlockState blockState : packedData.paletteEntries()) {
            transientState |= this.blockStatePolicy.isTransientPistonState(blockState);
            BlockState normalizedState = this.blockStatePolicy.normalizeState(blockState);
            CompoundTag tag = NbtUtils.writeBlockState(normalizedState);
            palette.add(tag);
            if (!AIR_BLOCK_ID.equals(tag.getString("Name").orElse(AIR_BLOCK_ID))) {
                nonAir = true;
            }
        }
        if (!nonAir) {
            return new CapturedSection(null, transientState);
        }
        long[] packedStorage = packedData.storage()
                .map(java.util.stream.LongStream::toArray)
                .orElseGet(() -> new long[0]);
        return new CapturedSection(
                new ChunkSectionSnapshotPayload(sectionY, palette, packedStorage, packedData.bitsPerEntry()),
                transientState
        );
    }

    private Map<Integer, CompoundTag> captureBlockEntities(ServerLevel level, LevelChunk chunk) {
        LinkedHashMap<Integer, CompoundTag> blockEntities = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockEntity blockEntity = entry.getValue();
            if (blockEntity == null) {
                continue;
            }
            BlockPos pos = entry.getKey();
            CompoundTag blockEntityTag = BlockEntitySnapshot.capture(level, blockEntity);
            if (blockEntityTag == null) {
                continue;
            }
            PersistentBlockStatePolicy.PersistentBlockState persistentState = this.blockStatePolicy.normalize(
                    chunk.getBlockState(pos),
                    blockEntityTag
            );
            if (persistentState.blockEntityTag() == null) {
                continue;
            }
            blockEntities.put(
                    SnapshotWriter.packVerticalIndex(pos.getY() - level.getMinY(), pos.getX() & 15, pos.getZ() & 15),
                    persistentState.blockEntityTag()
            );
        }
        return blockEntities;
    }

    private List<EntityPayload> captureEntities(
            ServerLevel level,
            LevelChunk chunk,
            EntitySnapshotOverride entityOverride
    ) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        LinkedHashMap<String, EntityPayload> snapshots = new LinkedHashMap<>();
        this.captureEntitiesInBounds(
                level,
                this.chunkBounds(level, chunkX, chunkZ, 0.0D),
                entity -> !this.isRemoved(entity),
                chunkX,
                chunkZ,
                snapshots
        );
        this.captureEntitiesInBounds(
                level,
                this.chunkBounds(level, chunkX, chunkZ, PLACED_ENTITY_SEARCH_MARGIN),
                entity -> !this.isRemoved(entity) && this.placedEntityHistoryPolicy.shouldPersist(entity),
                chunkX,
                chunkZ,
                snapshots
        );
        return (entityOverride == null ? EntitySnapshotOverride.none() : entityOverride).applyTo(
                new ArrayList<>(snapshots.values()),
                new ChunkPoint(chunkX, chunkZ)
        );
    }

    private void captureEntitiesInBounds(
            ServerLevel level,
            AABB bounds,
            Predicate<Entity> predicate,
            int chunkX,
            int chunkZ,
            Map<String, EntityPayload> snapshots
    ) {
        for (Entity entity : level.getEntities((Entity) null, bounds, predicate)) {
            EntityPayload payload = this.entitySnapshotService.capture(level, entity);
            if (payload != null && this.isInChunk(entity, payload, chunkX, chunkZ)) {
                snapshots.putIfAbsent(this.snapshotKey(entity, payload), payload);
            }
        }
    }

    private AABB chunkBounds(ServerLevel level, int chunkX, int chunkZ, double margin) {
        return new AABB(
                (chunkX << 4) - margin,
                level.getMinY(),
                (chunkZ << 4) - margin,
                (chunkX << 4) + 16 + margin,
                level.getMaxY() + 1,
                (chunkZ << 4) + 16 + margin
        );
    }

    private String snapshotKey(Entity entity, EntityPayload payload) {
        String entityId = payload.entityId();
        return entityId == null || entityId.isBlank() ? entity.getUUID().toString() : entityId;
    }

    private boolean isInChunk(Entity entity, EntityPayload payload, int chunkX, int chunkZ) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        if (this.placedEntityHistoryPolicy.shouldPersist(payload)) {
            return this.placedEntityHistoryPolicy.belongsToChunk(payload, chunkX, chunkZ);
        }
        BlockPos pos = entity.blockPosition();
        return (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ;
    }

    private boolean isRemoved(Entity entity) {
        return entity == null || entity.isRemoved();
    }

    record BlockStateOverride(BlockPos pos, BlockState state, CompoundTag blockEntityTag) {

        BlockStateOverride {
            pos = pos == null ? null : pos.immutable();
            blockEntityTag = blockEntityTag == null ? null : blockEntityTag.copy();
        }
    }

    record LoadedBlockStateCapture(ChunkSnapshotPayload payload, boolean transientState) {

        static LoadedBlockStateCapture captured(ChunkSnapshotPayload payload) {
            return new LoadedBlockStateCapture(payload, false);
        }

        static LoadedBlockStateCapture missing() {
            return new LoadedBlockStateCapture(null, false);
        }

        static LoadedBlockStateCapture transientCapture() {
            return new LoadedBlockStateCapture(null, true);
        }
    }

    private record CapturedSection(ChunkSectionSnapshotPayload payload, boolean transientState) {
    }

    private record NormalizedBlockStateOverride(
            BlockPos pos,
            PersistentBlockStatePolicy.PersistentBlockState state
    ) {
    }
}
