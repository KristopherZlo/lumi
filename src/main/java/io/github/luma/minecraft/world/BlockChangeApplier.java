package io.github.luma.minecraft.world;

import io.github.luma.domain.model.BlockChangeRecord;
import io.github.luma.domain.model.EntityPayload;
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

    private static final PersistentBlockStatePolicy BLOCK_STATE_POLICY = new PersistentBlockStatePolicy();

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
        applyPersistentBlockState(level, pos, state, blockEntityTag);
    }

    public static void applyStoredChange(ServerLevel level, StoredBlockChange change) throws IOException {
        applyPersistentBlockState(
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
            applyPersistentBlockState(
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

    private static void applyPersistentBlockState(ServerLevel level, BlockPos pos, BlockState state, CompoundTag blockEntityTag) {
        PersistentBlockStatePolicy.PersistentBlockState persistentState = BLOCK_STATE_POLICY.normalize(state, blockEntityTag);
        applyRawBlockState(level, pos, persistentState.state(), persistentState.blockEntityTag());
    }

    public static void applyChunkBlockEntities(ServerLevel level, ChunkBatch batch) {
        if (batch == null || batch.blockEntities().isEmpty()) {
            return;
        }

        applyBlockEntities(level, List.copyOf(batch.blockEntities().entrySet()), 0, batch.blockEntities().size());
    }

    public static int applyBlockEntities(
            ServerLevel level,
            List<Map.Entry<BlockPos, CompoundTag>> blockEntities,
            int startIndex,
            int maxBlockEntities
    ) {
        if (blockEntities == null || blockEntities.isEmpty() || maxBlockEntities <= 0 || startIndex >= blockEntities.size()) {
            return 0;
        }

        int endIndex = Math.min(blockEntities.size(), startIndex + maxBlockEntities);
        for (int index = startIndex; index < endIndex; index++) {
            Map.Entry<BlockPos, CompoundTag> entry = blockEntities.get(index);
            applyBlockEntity(level, entry.getKey(), entry.getValue());
        }
        return endIndex - startIndex;
    }

    public static void applyEntityBatch(ServerLevel level, EntityBatch entityBatch) {
        if (entityBatch == null || entityBatch.isEmpty()) {
            return;
        }

        applyEntityBatch(level, entityBatch, 0, entityOperationCount(entityBatch));
    }

    public static int applyEntityBatch(ServerLevel level, EntityBatch entityBatch, int startIndex, int maxEntities) {
        if (entityBatch == null || entityBatch.isEmpty() || maxEntities <= 0 || startIndex >= entityOperationCount(entityBatch)) {
            return 0;
        }

        int total = entityOperationCount(entityBatch);
        int endIndex = Math.min(total, startIndex + maxEntities);
        int removalCount = entityBatch.entityIdsToRemove().size();
        int updateCount = entityBatch.entitiesToUpdate().size();
        for (int index = startIndex; index < endIndex; index++) {
            if (index < removalCount) {
                removeEntity(level, entityBatch.entityIdsToRemove().get(index));
                continue;
            }

            int updateIndex = index - removalCount;
            if (updateIndex < updateCount) {
                CompoundTag entityTag = entityBatch.entitiesToUpdate().get(updateIndex);
                removeEntity(level, EntityPayload.readUuid(entityTag).map(UUID::toString).orElse(""));
                spawnEntity(level, entityTag);
                continue;
            }

            int spawnIndex = updateIndex - updateCount;
            spawnEntity(level, entityBatch.entitiesToSpawn().get(spawnIndex));
        }
        return endIndex - startIndex;
    }

    public static int entityOperationCount(EntityBatch entityBatch) {
        if (entityBatch == null) {
            return 0;
        }
        return entityBatch.entityIdsToRemove().size()
                + entityBatch.entitiesToUpdate().size()
                + entityBatch.entitiesToSpawn().size();
    }

    public static void applyBlockState(ServerLevel level, BlockPos pos, BlockState state, CompoundTag blockEntityTag) {
        PersistentBlockStatePolicy.PersistentBlockState persistentState = BLOCK_STATE_POLICY.normalize(state, blockEntityTag);
        applyRawBlockState(level, pos, persistentState.state(), persistentState.blockEntityTag());
    }

    private static void applyRawBlockState(ServerLevel level, BlockPos pos, BlockState state, CompoundTag blockEntityTag) {
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
        PersistentBlockStatePolicy.PersistentBlockState persistentState = BLOCK_STATE_POLICY.normalize(state, targetBlockEntityTag);
        state = persistentState.state();
        targetBlockEntityTag = persistentState.blockEntityTag();
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

        if (targetBlockEntityTag == null && !currentState.hasBlockEntity()) {
            return false;
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
