package io.github.luma.client.input;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.minecraft.capture.BlockEntitySnapshot;
import io.github.luma.minecraft.world.BlockStateNbtCodec;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Checks that a delegated native tool replay reached the Lumi action it claims
 * to cover before Lumi moves its own undo/redo stack.
 */
final class NativeUndoRedoVerifier {

    boolean matches(ServerLevel level, UndoRedoAction action, boolean undo) {
        if (level == null || action == null) {
            return false;
        }
        for (StoredBlockChange change : action.redoChanges()) {
            if (this.matchesBlock(level, change, undo)) {
                return true;
            }
        }
        for (StoredEntityChange change : action.redoEntityChanges()) {
            if (this.matchesEntity(level, change, undo)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesBlock(ServerLevel level, StoredBlockChange change, boolean undo) {
        if (change == null || change.pos() == null) {
            return false;
        }
        StatePayload target = undo ? change.oldValue() : change.newValue();
        if (target == null) {
            return false;
        }

        try {
            BlockPos pos = change.pos().toBlockPos();
            BlockState targetState = BlockStateNbtCodec.deserializeBlockState(level, target.stateTag());
            if (!level.getBlockState(pos).equals(targetState)) {
                return false;
            }

            CompoundTag targetBlockEntity = target.blockEntityTag();
            if (targetBlockEntity == null && !targetState.hasBlockEntity()) {
                return true;
            }
            BlockEntity currentBlockEntity = level.getBlockEntity(pos);
            if (currentBlockEntity == null) {
                return targetBlockEntity == null;
            }
            if (targetBlockEntity == null) {
                return false;
            }
            return Objects.equals(BlockEntitySnapshot.capture(level, currentBlockEntity), targetBlockEntity);
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean matchesEntity(ServerLevel level, StoredEntityChange change, boolean undo) {
        if (change == null) {
            return false;
        }
        EntityPayload target = undo ? change.oldValue() : change.newValue();
        Optional<UUID> entityId = this.entityUuid(change, target);
        if (entityId.isEmpty()) {
            return false;
        }

        Entity current = level.getEntity(entityId.get());
        if (target == null) {
            return current == null || current.isRemoved();
        }
        if (current == null || current.isRemoved()) {
            return false;
        }

        String targetType = target.entityType();
        return targetType == null
                || targetType.isBlank()
                || Objects.equals(BuiltInRegistries.ENTITY_TYPE.getKey(current.getType()).toString(), targetType);
    }

    private Optional<UUID> entityUuid(StoredEntityChange change, EntityPayload target) {
        if (target != null) {
            Optional<UUID> targetId = target.uuid();
            if (targetId.isPresent()) {
                return targetId;
            }
        }
        if (change.entityId() == null || change.entityId().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(change.entityId()));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
