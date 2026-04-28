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

    private final PersistentBlockStatePolicy blockStatePolicy = new PersistentBlockStatePolicy();
    private final EntitySnapshotService entitySnapshotService = new EntitySnapshotService();

    public Optional<ChunkSnapshotPayload> captureLoadedChunk(ServerLevel level, ChunkPoint chunk) {
        return this.captureLoadedChunk(level, chunk, null, null, null);
    }

    public Optional<ChunkSnapshotPayload> captureChunk(ServerLevel level, ChunkPoint chunk) {
        if (level == null || chunk == null) {
            return Optional.empty();
        }
        LevelChunk levelChunk = level.getChunk(chunk.x(), chunk.z());
        return Optional.of(this.capture(level, levelChunk, null, null, null, EntitySnapshotOverride.none()));
    }

    public Optional<ChunkSnapshotPayload> captureLoadedChunk(
            ServerLevel level,
            ChunkPoint chunk,
            BlockPos overridePos,
            BlockState overrideState,
            CompoundTag overrideBlockEntity
    ) {
        return this.captureLoadedChunk(level, chunk, overridePos, overrideState, overrideBlockEntity, EntitySnapshotOverride.none());
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
                overridePos,
                overrideState,
                overrideBlockEntity,
                new EntitySnapshotOverride(oldEntityPayload, newEntityPayload)
        );
    }

    private Optional<ChunkSnapshotPayload> captureLoadedChunk(
            ServerLevel level,
            ChunkPoint chunk,
            BlockPos overridePos,
            BlockState overrideState,
            CompoundTag overrideBlockEntity,
            EntitySnapshotOverride entityOverride
    ) {
        if (level == null || chunk == null) {
            return Optional.empty();
        }
        LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
        if (levelChunk == null) {
            return Optional.empty();
        }
        return Optional.of(this.capture(level, levelChunk, overridePos, overrideState, overrideBlockEntity, entityOverride));
    }

    private ChunkSnapshotPayload capture(
            ServerLevel level,
            LevelChunk chunk,
            BlockPos overridePos,
            BlockState overrideState,
            CompoundTag overrideBlockEntity,
            EntitySnapshotOverride entityOverride
    ) {
        BlockPos immutableOverridePos = overridePos == null ? null : overridePos.immutable();
        PersistentBlockStatePolicy.PersistentBlockState normalizedOverride = overrideState == null
                ? null
                : this.blockStatePolicy.normalize(overrideState, overrideBlockEntity);
        int overrideSectionY = immutableOverridePos == null ? Integer.MIN_VALUE : immutableOverridePos.getY() >> 4;
        List<ChunkSectionSnapshotPayload> sections = new ArrayList<>();

        LevelChunkSection[] chunkSections = chunk.getSections();
        for (int index = 0; index < chunkSections.length; index++) {
            LevelChunkSection section = chunkSections[index];
            if (section == null) {
                continue;
            }
            int sectionY = level.getSectionYFromSectionIndex(index);
            LevelChunkSection sectionCopy = section.copy();
            if (immutableOverridePos != null && sectionY == overrideSectionY && overrideState != null) {
                sectionCopy.setBlockState(
                        immutableOverridePos.getX() & 15,
                        immutableOverridePos.getY() & 15,
                        immutableOverridePos.getZ() & 15,
                        normalizedOverride.state()
                );
            }
            ChunkSectionSnapshotPayload sectionPayload = this.captureSection(sectionCopy, sectionY);
            if (sectionPayload != null) {
                sections.add(sectionPayload);
            }
        }

        Map<Integer, CompoundTag> blockEntities = this.captureBlockEntities(level, chunk);
        if (immutableOverridePos != null) {
            int packedIndex = SnapshotWriter.packVerticalIndex(
                    immutableOverridePos.getY() - level.getMinY(),
                    immutableOverridePos.getX() & 15,
                    immutableOverridePos.getZ() & 15
            );
            if (normalizedOverride == null || normalizedOverride.blockEntityTag() == null || normalizedOverride.state().isAir()) {
                blockEntities.remove(packedIndex);
            } else {
                blockEntities.put(packedIndex, normalizedOverride.blockEntityTag());
            }
        }

        return new ChunkSnapshotPayload(
                chunk.getPos().x,
                chunk.getPos().z,
                level.getMinY(),
                level.getMaxY(),
                sections,
                blockEntities,
                this.captureEntities(level, chunk, entityOverride)
        );
    }

    private ChunkSectionSnapshotPayload captureSection(LevelChunkSection section, int sectionY) {
        PalettedContainerRO.PackedData<BlockState> packedData = section.getStates().pack(BLOCK_STATE_STRATEGY);
        List<CompoundTag> palette = new ArrayList<>(packedData.paletteEntries().size());
        boolean nonAir = false;
        for (BlockState blockState : packedData.paletteEntries()) {
            BlockState normalizedState = this.blockStatePolicy.normalizeState(blockState);
            CompoundTag tag = NbtUtils.writeBlockState(normalizedState);
            palette.add(tag);
            if (!AIR_BLOCK_ID.equals(tag.getString("Name").orElse(AIR_BLOCK_ID))) {
                nonAir = true;
            }
        }
        if (!nonAir) {
            return null;
        }
        long[] packedStorage = packedData.storage()
                .map(java.util.stream.LongStream::toArray)
                .orElseGet(() -> new long[0]);
        return new ChunkSectionSnapshotPayload(sectionY, palette, packedStorage, packedData.bitsPerEntry());
    }

    private Map<Integer, CompoundTag> captureBlockEntities(ServerLevel level, LevelChunk chunk) {
        LinkedHashMap<Integer, CompoundTag> blockEntities = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockEntity blockEntity = entry.getValue();
            if (blockEntity == null) {
                continue;
            }
            BlockPos pos = entry.getKey();
            PersistentBlockStatePolicy.PersistentBlockState persistentState = this.blockStatePolicy.normalize(
                    chunk.getBlockState(pos),
                    blockEntity.saveWithFullMetadata(level.registryAccess())
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
        AABB bounds = new AABB(
                chunkX << 4,
                level.getMinY(),
                chunkZ << 4,
                (chunkX << 4) + 16,
                level.getMaxY() + 1,
                (chunkZ << 4) + 16
        );

        List<EntityPayload> snapshots = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, bounds, entity -> this.isInChunk(entity, chunkX, chunkZ))) {
            EntityPayload payload = this.entitySnapshotService.capture(level, entity);
            if (payload != null) {
                snapshots.add(payload);
            }
        }
        return (entityOverride == null ? EntitySnapshotOverride.none() : entityOverride).applyTo(snapshots);
    }

    private boolean isInChunk(Entity entity, int chunkX, int chunkZ) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        BlockPos pos = entity.blockPosition();
        return (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ;
    }
}
