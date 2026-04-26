package io.github.luma.minecraft.world;

import io.github.luma.domain.model.BlockChangeRecord;
import io.github.luma.domain.model.StoredBlockChange;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockChangeApplier {

    private BlockChangeApplier() {
    }

    public static void applyChanges(ServerLevel level, List<BlockChangeRecord> changes) throws IOException {
        applyChanges(level, changes, 0, changes == null ? 0 : changes.size());
    }

    public static int applyChanges(ServerLevel level, List<BlockChangeRecord> changes, int startIndex, int maxChanges) throws IOException {
        if (changes == null || changes.isEmpty() || maxChanges <= 0 || startIndex >= changes.size()) {
            return 0;
        }

        int endIndex = Math.min(changes.size(), startIndex + maxChanges);
        for (int index = startIndex; index < endIndex; index++) {
            applyChange(level, changes.get(index));
        }
        return endIndex - startIndex;
    }

    public static void applyChange(ServerLevel level, BlockChangeRecord change) throws IOException {
        BlockPos pos = change.pos().toBlockPos();
        BlockState state = BlockStateNbtCodec.deserializeBlockState(level, change.newState());
        CompoundTag blockEntityTag = BlockStateNbtCodec.deserializeBlockEntity(change.newBlockEntityNbt());
        applyBlockState(level, pos, state, blockEntityTag);
    }

    public static void applyStoredChange(ServerLevel level, StoredBlockChange change) throws IOException {
        applyBlockState(
                level,
                change.pos().toBlockPos(),
                BlockStateNbtCodec.deserializeBlockState(level, change.newValue().stateTag()),
                change.newValue().blockEntityTag() == null ? null : change.newValue().blockEntityTag().copy()
        );
    }

    public static int applyPreparedBatch(ServerLevel level, PreparedChunkBatch batch, int startIndex, int maxBlocks) {
        if (batch == null || batch.placements().isEmpty() || maxBlocks <= 0 || startIndex >= batch.placements().size()) {
            return 0;
        }

        int endIndex = Math.min(batch.placements().size(), startIndex + maxBlocks);
        for (int index = startIndex; index < endIndex; index++) {
            PreparedBlockPlacement placement = batch.placements().get(index);
            applyBlockState(
                    level,
                    placement.pos(),
                    placement.state(),
                    placement.blockEntityTag() == null ? null : placement.blockEntityTag().copy()
            );
        }
        return endIndex - startIndex;
    }

    public static int applySectionBatch(ServerLevel level, SectionBatch batch, int startIndex, int maxBlocks) {
        if (batch == null || batch.placements().isEmpty() || maxBlocks <= 0 || startIndex >= batch.placements().size()) {
            return 0;
        }

        int endIndex = Math.min(batch.placements().size(), startIndex + maxBlocks);
        for (int index = startIndex; index < endIndex; index++) {
            PreparedBlockPlacement placement = batch.placements().get(index);
            applyBlockStateOnly(level, placement.pos(), placement.state(), placement.blockEntityTag());
        }
        return endIndex - startIndex;
    }

    public static void applyChunkBlockEntities(ServerLevel level, ChunkBatch batch) {
        if (batch == null || batch.blockEntities().isEmpty()) {
            return;
        }

        for (Map.Entry<BlockPos, CompoundTag> entry : batch.blockEntities().entrySet()) {
            applyBlockEntity(level, entry.getKey(), entry.getValue());
        }
    }

    public static void applyEntityBatch(ServerLevel level, EntityBatch entityBatch) {
        if (entityBatch == null || entityBatch.isEmpty()) {
            return;
        }

        for (String entityId : entityBatch.entityIdsToRemove()) {
            removeEntity(level, entityId);
        }
        for (CompoundTag entityTag : entityBatch.entitiesToUpdate()) {
            removeEntity(level, entityTag.getString("UUID").orElse(""));
            spawnEntity(level, entityTag);
        }
        for (CompoundTag entityTag : entityBatch.entitiesToSpawn()) {
            spawnEntity(level, entityTag);
        }
    }

    public static void applyBlockState(ServerLevel level, BlockPos pos, BlockState state, CompoundTag blockEntityTag) {
        if (!requiresUpdate(level, pos, state, blockEntityTag)) {
            return;
        }

        level.removeBlockEntity(pos);
        level.setBlock(pos, state, 3);

        if (blockEntityTag != null) {
            BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, blockEntityTag.copy(), level.registryAccess());
            if (blockEntity != null) {
                level.setBlockEntity(blockEntity);
            }
        }
    }

    public static void applyBlockStateOnly(ServerLevel level, BlockPos pos, BlockState state, CompoundTag targetBlockEntityTag) {
        if (!requiresUpdate(level, pos, state, targetBlockEntityTag)) {
            return;
        }

        level.removeBlockEntity(pos);
        level.setBlock(pos, state, 3);
    }

    public static void applyBlockEntity(ServerLevel level, BlockPos pos, CompoundTag blockEntityTag) {
        if (blockEntityTag == null) {
            return;
        }

        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, blockEntityTag.copy(), level.registryAccess());
        if (blockEntity != null) {
            level.setBlockEntity(blockEntity);
        }
    }

    private static boolean requiresUpdate(ServerLevel level, BlockPos pos, BlockState targetState, CompoundTag targetBlockEntityTag) {
        BlockState currentState = level.getBlockState(pos);
        if (!currentState.equals(targetState)) {
            return true;
        }

        BlockEntity currentBlockEntity = level.getBlockEntity(pos);
        if (currentBlockEntity == null) {
            return targetBlockEntityTag != null;
        }
        if (targetBlockEntityTag == null) {
            return true;
        }

        CompoundTag currentBlockEntityTag = currentBlockEntity.saveWithFullMetadata(level.registryAccess());
        return !Objects.equals(currentBlockEntityTag, targetBlockEntityTag);
    }

    private static void removeEntity(ServerLevel level, String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return;
        }
        try {
            Entity entity = level.getEntity(UUID.fromString(entityId));
            if (entity == null || entity instanceof ServerPlayer) {
                return;
            }
            entity.discard();
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static void spawnEntity(ServerLevel level, CompoundTag entityTag) {
        if (entityTag == null || entityTag.isEmpty()) {
            return;
        }
        Entity entity = EntityType.loadEntityRecursive(
                entityTag.copy(),
                level,
                EntitySpawnReason.LOAD,
                EntityProcessor.NOP
        );
        if (entity == null || entity instanceof ServerPlayer) {
            return;
        }
        level.tryAddFreshEntityWithPassengers(entity);
    }
}
