package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/** Resolves durable entity types and canonical NBT before tick-time apply. */
public final class MinecraftEntityStateDecoder {
    private final Registry<EntityType<?>> types;

    public MinecraftEntityStateDecoder(Registry<EntityType<?>> types) {
        this.types = Objects.requireNonNull(types, "types");
    }

    public DecodedEntityChunk decode(EntityChunkBlob source) throws IOException {
        return decodeNormalized(normalize(source));
    }

    /** Removes passenger records duplicated by older capture code beside their root vehicle. */
    public EntityChunkBlob normalize(EntityChunkBlob source) throws IOException {
        Objects.requireNonNull(source, "source");
        Set<UUID> passengerIds = new HashSet<>();
        for (EntityState entity : source.entities()) {
            collectPassengerIds(MinecraftNbtCodec.decode(entity.nbt()), passengerIds);
        }
        return withoutTopLevelPassengers(source, passengerIds);
    }

    /** Normalizes the complete Restore state so cross-chunk passenger trees remain atomic. */
    public Map<EntityChunkKey, EntityChunkBlob> normalize(
            Map<EntityChunkKey, EntityChunkBlob> source) throws IOException {
        Objects.requireNonNull(source, "source");
        Set<UUID> passengerIds = new HashSet<>();
        for (EntityChunkBlob chunk : source.values()) {
            for (EntityState entity : chunk.entities()) {
                collectPassengerIds(MinecraftNbtCodec.decode(entity.nbt()), passengerIds);
            }
        }
        Map<EntityChunkKey, EntityChunkBlob> normalized = new HashMap<>();
        source.forEach((key, chunk) ->
                normalized.put(key, withoutTopLevelPassengers(chunk, passengerIds)));
        return Map.copyOf(normalized);
    }

    private static EntityChunkBlob withoutTopLevelPassengers(
            EntityChunkBlob source, Set<UUID> passengerIds) {
        if (passengerIds.isEmpty()) {
            return source;
        }
        return new EntityChunkBlob(source.entities().stream()
                .filter(entity -> !passengerIds.contains(entity.id()))
                .toList());
    }

    DecodedEntityChunk decodeNormalized(EntityChunkBlob source) throws IOException {
        var decoded = new ArrayList<DecodedEntity>(source.entities().size());
        for (var entity : source.entities()) {
            final Identifier identifier;
            try {
                identifier = Identifier.parse(entity.type());
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid persistent entity type: " + entity.type(), invalid);
            }
            EntityType<?> type = types.getOptional(identifier).orElseThrow(
                    () -> new IOException("Missing persistent entity type: " + entity.type()));
            var nbt = MinecraftNbtCodec.decode(entity.nbt());
            nbt.putString("id", entity.type());
            nbt.putIntArray("UUID", UUIDUtil.uuidToIntArray(entity.id()));
            decoded.add(new DecodedEntity(entity.id(), type, nbt));
        }
        return new DecodedEntityChunk(decoded);
    }

    private static void collectPassengerIds(CompoundTag parent, Set<UUID> passengerIds) {
        ListTag passengers = parent.getListOrEmpty("Passengers");
        for (int index = 0; index < passengers.size(); index++) {
            passengers.getCompound(index).ifPresent(passenger -> {
                passenger.getIntArray("UUID")
                        .filter(parts -> parts.length == 4)
                        .map(UUIDUtil::uuidFromIntArray)
                        .ifPresent(passengerIds::add);
                collectPassengerIds(passenger, passengerIds);
            });
        }
    }
}
