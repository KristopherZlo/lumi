package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

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

    public Optional<ChunkSnapshotPayload> captureLoadedChunk(ServerLevel level, ChunkPoint chunk) {
        return this.captureLoadedChunk(level, chunk, null, null, null);
    }

    public Optional<ChunkSnapshotPayload> captureLoadedChunk(
            ServerLevel level,
            ChunkPoint chunk,
            BlockPos overridePos,
            BlockState overrideState,
            CompoundTag overrideBlockEntity
    ) {
        if (level == null || chunk == null) {
            return Optional.empty();
        }
        LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
        if (levelChunk == null) {
            return Optional.empty();
        }
        return Optional.of(this.capture(level, levelChunk, overridePos, overrideState, overrideBlockEntity));
    }

    private ChunkSnapshotPayload capture(
            ServerLevel level,
            LevelChunk chunk,
            BlockPos overridePos,
            BlockState overrideState,
            CompoundTag overrideBlockEntity
    ) {
        BlockPos immutableOverridePos = overridePos == null ? null : overridePos.immutable();
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
                        overrideState
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
            if (overrideBlockEntity == null || overrideState == null || overrideState.isAir()) {
                blockEntities.remove(packedIndex);
            } else {
                blockEntities.put(packedIndex, overrideBlockEntity.copy());
            }
        }

        return new ChunkSnapshotPayload(
                chunk.getPos().x,
                chunk.getPos().z,
                level.getMinY(),
                level.getMaxY(),
                sections,
                blockEntities
        );
    }

    private ChunkSectionSnapshotPayload captureSection(LevelChunkSection section, int sectionY) {
        PalettedContainerRO.PackedData<BlockState> packedData = section.getStates().pack(BLOCK_STATE_STRATEGY);
        List<CompoundTag> palette = new ArrayList<>(packedData.paletteEntries().size());
        boolean nonAir = false;
        for (BlockState blockState : packedData.paletteEntries()) {
            CompoundTag tag = NbtUtils.writeBlockState(blockState);
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
            blockEntities.put(
                    SnapshotWriter.packVerticalIndex(pos.getY() - level.getMinY(), pos.getX() & 15, pos.getZ() & 15),
                    blockEntity.saveWithFullMetadata(level.registryAccess())
            );
        }
        return blockEntities;
    }
}
