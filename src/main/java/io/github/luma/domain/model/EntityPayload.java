package io.github.luma.domain.model;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

public record EntityPayload(
        CompoundTag entityTag
) {

    public EntityPayload {
        entityTag = entityTag == null ? new CompoundTag() : entityTag.copy();
    }

    public String entityId() {
        return readUuid(this.entityTag)
                .map(UUID::toString)
                .orElseGet(() -> this.entityTag.getString("UUID").orElse(""));
    }

    public String entityType() {
        return this.entityTag.getString("id").orElse("");
    }

    public ChunkPoint chunk() {
        ListTag pos = this.entityTag.getListOrEmpty("Pos");
        if (pos.size() >= 3) {
            int chunkX = floorToBlock(pos.getDoubleOr(0, 0.0D)) >> 4;
            int chunkZ = floorToBlock(pos.getDoubleOr(2, 0.0D)) >> 4;
            return new ChunkPoint(chunkX, chunkZ);
        }
        return new ChunkPoint(0, 0);
    }

    public CompoundTag copyTag() {
        return this.entityTag.copy();
    }

    private static int floorToBlock(double coordinate) {
        int truncated = (int) coordinate;
        return coordinate < truncated ? truncated - 1 : truncated;
    }

    private static Optional<UUID> readUuid(CompoundTag tag) {
        Optional<int[]> rawUuid = tag.getIntArray("UUID");
        if (rawUuid.isPresent() && rawUuid.get().length == 4) {
            int[] values = rawUuid.get();
            long most = ((long) values[0] << 32) | (values[1] & 0xFFFFFFFFL);
            long least = ((long) values[2] << 32) | (values[3] & 0xFFFFFFFFL);
            return Optional.of(new UUID(most, least));
        }
        return tag.getString("UUID").flatMap(value -> {
            try {
                return Optional.of(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        });
    }
}
