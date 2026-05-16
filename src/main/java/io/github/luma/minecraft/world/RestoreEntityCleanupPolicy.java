package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.minecraft.capture.PlacedEntityHistoryPolicy;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

final class RestoreEntityCleanupPolicy {

    private static final String PLAYER_ENTITY_TYPE = "minecraft:player";

    private final PlacedEntityHistoryPolicy placedEntityHistoryPolicy;

    RestoreEntityCleanupPolicy() {
        this(new PlacedEntityHistoryPolicy());
    }

    RestoreEntityCleanupPolicy(PlacedEntityHistoryPolicy placedEntityHistoryPolicy) {
        this.placedEntityHistoryPolicy = placedEntityHistoryPolicy == null
                ? new PlacedEntityHistoryPolicy()
                : placedEntityHistoryPolicy;
    }

    boolean shouldInspectExtraEntity(Entity entity) {
        if (entity == null || entity instanceof ServerPlayer || entity.getType() == null) {
            return false;
        }
        return this.shouldInspectExtraEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }

    boolean shouldInspectExtraEntityType(String entityType) {
        return entityType != null && !entityType.isBlank() && !PLAYER_ENTITY_TYPE.equals(entityType);
    }

    boolean shouldRemoveExtraEntity(EntityPayload payload, ChunkPoint authoritativeChunk, Set<String> targetEntityIds) {
        if (payload == null || authoritativeChunk == null) {
            return false;
        }
        if (!this.placedEntityHistoryPolicy.belongsToChunk(payload, authoritativeChunk.x(), authoritativeChunk.z())) {
            return false;
        }
        Set<String> targetIds = targetEntityIds == null ? Set.of() : targetEntityIds;
        return !targetIds.contains(payload.entityId());
    }
}
