package io.github.lumi.minecraft.world;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private static final Comparator<EntityChunkKey> CHUNK_ORDER =
            Comparator.comparingInt(EntityChunkKey::chunkX)
                    .thenComparingInt(EntityChunkKey::chunkZ);
    private final Registry<EntityType<?>> types;
    private final MinecraftEntityNbtCanonicalizer canonicalizer;

    public MinecraftEntityStateDecoder(Registry<EntityType<?>> types) {
        this(types, new MinecraftEntityNbtCanonicalizer());
    }

    MinecraftEntityStateDecoder(
            Registry<EntityType<?>> types,
            MinecraftEntityNbtCanonicalizer canonicalizer) {
        this.types = Objects.requireNonNull(types, "types");
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
    }

    public DecodedEntityChunk decode(EntityChunkBlob source) throws IOException {
        return decodeNormalized(normalize(source));
    }

    /** Removes passenger records duplicated by older capture code beside their root vehicle. */
    public EntityChunkBlob normalize(EntityChunkBlob source) throws IOException {
        Objects.requireNonNull(source, "source");
        Set<UUID> passengerIds = new HashSet<>();
        EntityChunkBlob normalized = normalizePayloads(source, passengerIds);
        return withoutTopLevelPassengers(normalized, passengerIds);
    }

    /** Normalizes the complete Restore state so cross-chunk passenger trees remain atomic. */
    public Map<EntityChunkKey, EntityChunkBlob> normalize(
            Map<EntityChunkKey, EntityChunkBlob> source) throws IOException {
        Objects.requireNonNull(source, "source");
        Set<UUID> passengerIds = new HashSet<>();
        Map<EntityChunkKey, EntityChunkBlob> normalized = new LinkedHashMap<>();
        for (var entry : source.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(CHUNK_ORDER)).toList()) {
            normalized.put(entry.getKey(), normalizePayloads(entry.getValue(), passengerIds));
        }
        normalized.replaceAll((key, chunk) -> withoutTopLevelPassengers(chunk, passengerIds));
        int duplicates = removeDuplicateRoots(normalized);
        if (duplicates != 0) {
            LumiMod.LOGGER.warn(
                    "Lumi removed {} duplicate entity UUID records across saved chunks",
                    duplicates);
        }
        return Map.copyOf(normalized);
    }

    private static int removeDuplicateRoots(
            Map<EntityChunkKey, EntityChunkBlob> chunks) {
        Set<UUID> seen = new HashSet<>();
        int duplicates = 0;
        for (var entry : chunks.entrySet()) {
            var unique = new ArrayList<EntityState>(entry.getValue().entities().size());
            for (EntityState entity : entry.getValue().entities()) {
                if (seen.add(entity.id())) {
                    unique.add(entity);
                } else {
                    duplicates++;
                }
            }
            if (unique.size() != entry.getValue().entities().size()) {
                entry.setValue(new EntityChunkBlob(unique));
            }
        }
        return duplicates;
    }

    private EntityChunkBlob normalizePayloads(
            EntityChunkBlob source, Set<UUID> passengerIds) throws IOException {
        var normalized = new ArrayList<EntityState>(source.entities().size());
        for (EntityState entity : source.entities()) {
            EntityType<?> type = resolveType(entity.type());
            CompoundTag payload = canonicalizer.normalize(
                    MinecraftNbtCodec.decode(entity.nbt()), type);
            collectPassengerIds(payload, passengerIds);
            var canonical = MinecraftNbtCodec.encode(payload);
            normalized.add(canonical.equals(entity.nbt()) ? entity
                    : new EntityState(entity.id(), entity.type(), canonical));
        }
        return new EntityChunkBlob(normalized);
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
            EntityType<?> type = resolveType(entity.type());
            var nbt = MinecraftNbtCodec.decode(entity.nbt());
            nbt.putString("id", entity.type());
            nbt.putIntArray("UUID", UUIDUtil.uuidToIntArray(entity.id()));
            decoded.add(new DecodedEntity(entity, type, nbt));
        }
        return new DecodedEntityChunk(decoded);
    }

    private EntityType<?> resolveType(String value) throws IOException {
        final Identifier identifier;
        try {
            identifier = Identifier.parse(value);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid persistent entity type: " + value, invalid);
        }
        return types.getOptional(identifier).orElseThrow(
                () -> new IOException("Missing persistent entity type: " + value));
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
