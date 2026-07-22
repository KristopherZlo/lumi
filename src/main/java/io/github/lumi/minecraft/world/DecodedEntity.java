package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityState;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;

/** Minecraft-native durable entity payload decoded before tick-time apply. */
public record DecodedEntity(EntityState state, EntityType<?> type, CompoundTag nbt) {
    public DecodedEntity {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(type, "type");
        nbt = Objects.requireNonNull(nbt, "nbt").copy();
    }

    public UUID id() {
        return state.id();
    }
}
