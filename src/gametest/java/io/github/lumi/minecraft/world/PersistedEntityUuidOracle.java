package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.mixin.EntityStoragePersistenceAccessor;
import io.github.lumi.mixin.MinecraftServerAccessor;
import io.github.lumi.mixin.PersistentEntityManagerPersistenceAccessor;
import io.github.lumi.mixin.ServerLevelEntityManagerAccessor;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/** GameTest-only global UUID uniqueness check over persisted vanilla entities. */
public final class PersistedEntityUuidOracle {
    private static final int REGION_HEADER_ENTRIES = 32 * 32;
    private static final Pattern REGION_FILE =
            Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private final List<EntityStore> stores;
    private final MinecraftEntityChunkCapture capture =
            new MinecraftEntityChunkCapture();

    private PersistedEntityUuidOracle(List<EntityStore> stores) {
        this.stores = List.copyOf(Objects.requireNonNull(stores, "stores"));
    }

    /** Captures every server dimension's storage handles on the server thread. */
    public static PersistedEntityUuidOracle open(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        var source = ((MinecraftServerAccessor) server).lumi$storageSource();
        List<EntityStore> stores = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            stores.add(new EntityStore(
                    level.dimension().identifier().toString(),
                    entityStorage(level),
                    source.getDimensionPath(level.dimension()).resolve("entities")));
        }
        return new PersistedEntityUuidOracle(stores);
    }

    /** Forces pending writes, then checks every dimension and entry sequentially. */
    public Result audit() throws IOException {
        for (EntityStore store : stores) {
            MinecraftPersistenceFuture.join(
                    store.storage().synchronize(true),
                    "Persisted entity UUID audit sync " + store.dimension());
        }
        Map<UUID, Location> locations = new HashMap<>();
        int chunks = 0;
        int entities = 0;
        for (EntityStore store : stores) {
            if (!Files.isDirectory(store.directory())) {
                continue;
            }
            try (DirectoryStream<Path> regions =
                         Files.newDirectoryStream(
                                 store.directory(), "r.*.*.mca")) {
                for (Path region : regions) {
                    Counts checked = auditRegion(store, region, locations);
                    chunks = Math.addExact(chunks, checked.chunks());
                    entities = Math.addExact(entities, checked.entities());
                }
            }
        }
        return new Result(chunks, entities);
    }

    private Counts auditRegion(
            EntityStore store,
            Path region,
            Map<UUID, Location> locations) throws IOException {
        Matcher name = REGION_FILE.matcher(region.getFileName().toString());
        if (!name.matches()) {
            return new Counts(0, 0);
        }
        int regionX = coordinate(name.group(1), region);
        int regionZ = coordinate(name.group(2), region);
        int chunks = 0;
        int entities = 0;
        try (var input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(region)))) {
            for (int index = 0; index < REGION_HEADER_ENTRIES; index++) {
                if (input.readInt() == 0) {
                    continue;
                }
                EntityChunkKey key = new EntityChunkKey(
                        Math.addExact(Math.multiplyExact(regionX, 32), index & 31),
                        Math.addExact(Math.multiplyExact(regionZ, 32), index >>> 5));
                var stored = MinecraftPersistenceFuture.join(
                        store.storage().read(
                                new ChunkPos(key.chunkX(), key.chunkZ())),
                        "Persisted entity UUID audit read "
                                + store.dimension() + " " + key);
                capture.captureStored(key, stored);
                if (stored.isPresent()) {
                    ListTag roots = stored.orElseThrow().getList("Entities")
                            .orElseThrow(() -> new IOException(
                                    "Persisted entity chunk has no Entities list: " + key));
                    entities = Math.addExact(
                            entities, inspect(
                                    roots,
                                    new Location(store.dimension(), key),
                                    locations));
                }
                chunks++;
            }
        } catch (ArithmeticException invalidCoordinate) {
            throw new IOException("Invalid entity region coordinates: " + region,
                    invalidCoordinate);
        }
        return new Counts(chunks, entities);
    }

    private static int inspect(
            ListTag entities,
            Location location,
            Map<UUID, Location> locations) throws IOException {
        int count = 0;
        for (int index = 0; index < entities.size(); index++) {
            CompoundTag entity = entities.getCompound(index).orElse(null);
            if (entity == null) {
                throw new IOException(
                        "Malformed persisted entity " + index + " in " + location);
            }
            UUID id = entity.read("UUID", UUIDUtil.CODEC).orElseThrow(() ->
                    new IOException(
                            "Persisted entity has no valid UUID in " + location));
            Location previous = locations.putIfAbsent(id, location);
            if (previous != null) {
                throw new IOException("Duplicate persisted entity UUID " + id
                        + " in " + previous + " and " + location);
            }
            count++;
            var passengers = entity.getList("Passengers");
            if (entity.contains("Passengers") && passengers.isEmpty()) {
                throw new IOException(
                        "Malformed persisted Passengers list in " + location);
            }
            if (passengers.isPresent()) {
                count = Math.addExact(
                        count, inspect(
                                passengers.orElseThrow(), location, locations));
            }
        }
        return count;
    }

    private static int coordinate(String value, Path region) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid entity region filename: " + region, invalid);
        }
    }

    @SuppressWarnings("unchecked")
    private static SimpleRegionStorage entityStorage(ServerLevel level) {
        var manager = (PersistentEntityManagerPersistenceAccessor<Entity>)
                ((ServerLevelEntityManagerAccessor) level).lumi$entityManager();
        return ((EntityStoragePersistenceAccessor) manager.lumi$permanentStorage())
                .lumi$simpleRegionStorage();
    }

    public record Result(int chunks, int entities) { }

    private record EntityStore(
            String dimension, SimpleRegionStorage storage, Path directory) { }
    private record Location(String dimension, EntityChunkKey chunk) { }
    private record Counts(int chunks, int entities) { }
}
