package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * Identifies persistent decorative/builder entities that must round-trip with
 * world history.
 */
public final class PlacedEntityHistoryPolicy {

    private static final Set<String> PLACED_ENTITY_TYPES = Set.of(
            "minecraft:armor_stand",
            "minecraft:block_display",
            "minecraft:glow_item_frame",
            "minecraft:interaction",
            "minecraft:item_display",
            "minecraft:item_frame",
            "minecraft:painting",
            "minecraft:text_display"
    );

    public boolean shouldPersist(EntityPayload payload) {
        return payload != null && this.shouldPersist(payload.entityType());
    }

    public boolean shouldPersist(Entity entity) {
        if (entity == null || entity.getType() == null) {
            return false;
        }
        return this.shouldPersist(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }

    public boolean shouldPersist(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return false;
        }
        return PLACED_ENTITY_TYPES.contains(entityType) || entityType.endsWith("_display");
    }

    public BlockPos historyBlockPos(EntityPayload payload) {
        if (payload == null) {
            return BlockPos.ZERO;
        }
        CompoundTag tag = payload.entityTag();
        if (tag.contains("TileX") && tag.contains("TileY") && tag.contains("TileZ")) {
            return new BlockPos(
                    tag.getInt("TileX").orElse(0),
                    tag.getInt("TileY").orElse(0),
                    tag.getInt("TileZ").orElse(0)
            );
        }
        if (tag.contains("block_pos")) {
            int[] packed = tag.getIntArray("block_pos").orElse(new int[0]);
            if (packed.length >= 3) {
                return new BlockPos(packed[0], packed[1], packed[2]);
            }
        }
        return payload.blockPos();
    }

    public boolean belongsToChunk(EntityPayload payload, int chunkX, int chunkZ) {
        if (payload == null) {
            return false;
        }
        BlockPos pos = this.shouldPersist(payload) ? this.historyBlockPos(payload) : payload.blockPos();
        return (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ;
    }
}
