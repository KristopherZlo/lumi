package io.github.lumi.minecraft.world;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;

/** Minecraft-native durable entity payload decoded before tick-time apply. */
public record DecodedEntity(UUID id, EntityType<?> type, CompoundTag nbt) {
    public DecodedEntity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        nbt = Objects.requireNonNull(nbt, "nbt").copy();
    }
}
