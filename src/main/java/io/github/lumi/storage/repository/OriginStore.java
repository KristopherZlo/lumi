package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class OriginStore {
    private static final int LEGACY_MAGIC = 0x4C4F5232;
    private static final int SHARD_MAGIC = 0x4C4F5332;
    private static final int REGION_SIZE = 32;
    private static final int MAX_SHARD_ENTRIES = 100_000;
    private static final long MAX_SHARD_BYTES = 4L * Integer.BYTES
            + MAX_SHARD_ENTRIES * (1L + 3L * Integer.BYTES + 32L);
    private static final Comparator<HistoryKey> KEY_ORDER = Comparator
            .comparingInt(OriginStore::chunkX)
            .thenComparingInt(OriginStore::chunkZ)
            .thenComparingInt(key -> key instanceof SectionKey ? 1 : 2)
            .thenComparingInt(key -> key instanceof SectionKey section
                    ? section.sectionY() : 0);
    private static final long SECTION_ENTRY_BYTES = Integer.BYTES + 1L
            + 3L * Integer.BYTES + 32L;
    private static final long ENTITY_ENTRY_BYTES = Integer.BYTES + 1L
            + 2L * Integer.BYTES + 32L;
    private final Path originsDirectory;
    private final Map<RegionCoordinate, CachedShard> shardCache =
            new HashMap<>();

    public OriginStore(Path dimensionRepository) {
        originsDirectory = Objects.requireNonNull(dimensionRepository, "dimensionRepository").resolve("origins");
    }

    public synchronized boolean register(HistoryKey key, ObjectId origin) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(origin, "origin");
        return registerAll(Map.of(key, origin)) == 1;
    }

    public synchronized int registerAll(
            Map<? extends HistoryKey, ObjectId> registrations) throws IOException {
        Objects.requireNonNull(registrations, "registrations");
        Map<RegionCoordinate, Map<HistoryKey, ObjectId>> byRegion = new TreeMap<>();
        for (var registration : registrations.entrySet()) {
            HistoryKey key = Objects.requireNonNull(registration.getKey(), "origin key");
            ObjectId id = Objects.requireNonNull(registration.getValue(), "origin id");
            byRegion.computeIfAbsent(region(key), ignored -> new HashMap<>()).put(key, id);
        }

        int added = 0;
        for (var region : byRegion.entrySet()) {
            Map<HistoryKey, ObjectId> combined = new HashMap<>(readShard(region.getKey()));
            boolean changed = false;
            for (var registration : region.getValue().entrySet()) {
                Optional<ObjectId> legacy = readLegacy(registration.getKey());
                if (legacy.isPresent()) {
                    requireSame(registration.getKey(), legacy.orElseThrow(),
                            registration.getValue());
                    continue;
                }
                ObjectId previous = combined.putIfAbsent(
                        registration.getKey(), registration.getValue());
                if (previous != null) {
                    requireSame(registration.getKey(), previous, registration.getValue());
                    continue;
                }
                changed = true;
                added++;
            }
            if (changed) {
                if (combined.size() > MAX_SHARD_ENTRIES) {
                    throw new IOException("Origin shard entry limit exceeded");
                }
                Map<HistoryKey, ObjectId> durable = Map.copyOf(combined);
                byte[] payload = encodeShard(region.getKey(), durable);
                AtomicFileWriter.replace(shardPath(region.getKey()), payload);
                shardCache.put(region.getKey(),
                        new CachedShard(payload.length, durable));
            }
        }
        return added;
    }

    public synchronized Optional<ObjectId> read(HistoryKey key) throws IOException {
        Objects.requireNonNull(key, "key");
        Optional<ObjectId> legacy = readLegacy(key);
        ObjectId sharded = readShard(region(key)).get(key);
        if (legacy.isPresent() && sharded != null) {
            requireSame(key, legacy.orElseThrow(), sharded);
        }
        return legacy.isPresent() ? legacy : Optional.ofNullable(sharded);
    }

    public synchronized Set<ObjectId> allOrigins() throws IOException {
        return Set.copyOf(entries().values());
    }

    /**
     * Reads origin membership from canonical paths without loading immutable
     * payloads. Payload readers still validate the stored key and object ID.
     */
    public synchronized Set<HistoryKey> keys() throws IOException {
        Set<HistoryKey> keys = new HashSet<>();
        if (Files.exists(originsDirectory)) {
            try (var files = Files.walk(originsDirectory)) {
                for (Path file : files
                        .filter(path -> path.getFileName().toString().endsWith(".origin"))
                        .toList()) {
                    if (!keys.add(keyFromPath(file))) {
                        throw new IOException("Duplicate origin entry for " + file);
                    }
                }
            }
        }
        for (HistoryKey key : shardEntries().keySet()) {
            keys.add(key);
        }
        return Set.copyOf(keys);
    }

    public synchronized Map<HistoryKey, ObjectId> entries() throws IOException {
        Map<HistoryKey, ObjectId> origins = new HashMap<>(legacyEntries(originsDirectory));
        merge(origins, shardEntries());
        return Map.copyOf(origins);
    }

    public synchronized Map<EntityChunkKey, ObjectId> entityEntries()
            throws IOException {
        Map<EntityChunkKey, ObjectId> entities = new HashMap<>();
        for (var entry : entries().entrySet()) {
            if (entry.getKey() instanceof EntityChunkKey key) {
                entities.put(key, entry.getValue());
            }
        }
        return Map.copyOf(entities);
    }

    private Map<HistoryKey, ObjectId> legacyEntries(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return Map.of();
        }
        Map<HistoryKey, ObjectId> origins = new HashMap<>();
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".origin")).toList()) {
                HistoryKey key = keyFromPath(file);
                OriginEntry entry = new OriginEntry(
                        key, decode(key, Files.readAllBytes(file)));
                if (origins.put(entry.key(), entry.id()) != null) {
                    throw new IOException("Duplicate origin entry for " + entry.key());
                }
            }
        }
        return Map.copyOf(origins);
    }

    private Optional<ObjectId> readLegacy(HistoryKey key) throws IOException {
        Path path = legacyPath(key);
        return Files.exists(path)
                ? Optional.of(decode(key, Files.readAllBytes(path)))
                : Optional.empty();
    }

    private byte[] encodeShard(
            RegionCoordinate region, Map<HistoryKey, ObjectId> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(SHARD_MAGIC);
            output.writeInt(region.x());
            output.writeInt(region.z());
            output.writeInt(entries.size());
            for (var entry : entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(KEY_ORDER)).toList()) {
                writeKey(output, entry.getKey());
                output.write(HexFormat.of().parseHex(entry.getValue().hex()));
            }
        }
        return bytes.toByteArray();
    }

    private ObjectId decode(HistoryKey expected, byte[] payload) throws IOException {
        OriginEntry entry = decode(payload);
        if (!entry.key().equals(expected)) {
            throw new IOException("Invalid origin entry for " + expected);
        }
        return entry.id();
    }

    private OriginEntry decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != LEGACY_MAGIC) {
                throw new IOException("Not a Lumi V2 origin entry");
            }
            HistoryKey stored = readKey(input);
            ObjectId id = readId(input);
            if (input.available() != 0) {
                throw new IOException("Invalid origin entry for " + stored);
            }
            return new OriginEntry(stored, id);
        }
    }

    private Map<HistoryKey, ObjectId> readShard(RegionCoordinate expected)
            throws IOException {
        CachedShard cached = shardCache.get(expected);
        Path path = shardPath(expected);
        if (!Files.exists(path)) {
            if (cached == null) {
                cached = new CachedShard(0, Map.of());
                shardCache.put(expected, cached);
            }
            return cached.entries();
        }
        long bytes = requireShardSize(path);
        if (cached != null && cached.bytes() == bytes) {
            return cached.entries();
        }
        Map<HistoryKey, ObjectId> entries =
                decodeShard(expected, Files.readAllBytes(path));
        shardCache.put(expected, new CachedShard(bytes, entries));
        return entries;
    }

    private Map<HistoryKey, ObjectId> decodeShard(
            RegionCoordinate expected, byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != SHARD_MAGIC) {
                throw new IOException("Not a Lumi origin shard");
            }
            RegionCoordinate stored = new RegionCoordinate(input.readInt(), input.readInt());
            if (!stored.equals(expected)) {
                throw new IOException("Origin shard is stored in the wrong region");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_SHARD_ENTRIES) {
                throw new IOException("Invalid origin shard entry count: " + count);
            }
            Map<HistoryKey, ObjectId> entries = new HashMap<>();
            HistoryKey previous = null;
            for (int index = 0; index < count; index++) {
                HistoryKey key = readKey(input);
                if (!region(key).equals(expected)
                        || previous != null && KEY_ORDER.compare(previous, key) >= 0) {
                    throw new IOException("Invalid origin shard key order: " + key);
                }
                entries.put(key, readId(input));
                previous = key;
            }
            if (input.available() != 0) {
                throw new IOException("Trailing data in origin shard");
            }
            return Map.copyOf(entries);
        }
    }

    private Map<HistoryKey, ObjectId> shardEntries() throws IOException {
        Path regions = originsDirectory.resolve("regions");
        if (!Files.exists(regions)) {
            return Map.of();
        }
        Map<HistoryKey, ObjectId> origins = new HashMap<>();
        try (var files = Files.walk(regions)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".bin")).toList()) {
                RegionCoordinate region = regionFromPath(file);
                merge(origins, readShard(region));
            }
        }
        return Map.copyOf(origins);
    }

    private Path legacyPath(HistoryKey key) {
        if (key instanceof SectionKey section) {
            return originsDirectory.resolve("sections")
                    .resolve(Integer.toString(section.chunkX()))
                    .resolve(Integer.toString(section.chunkZ()))
                    .resolve(section.sectionY() + ".origin");
        }
        EntityChunkKey entities = (EntityChunkKey) key;
        return originsDirectory.resolve("entities")
                .resolve(Integer.toString(entities.chunkX()))
                .resolve(entities.chunkZ() + ".origin");
    }

    private Path shardPath(RegionCoordinate region) {
        return originsDirectory.resolve("regions")
                .resolve(Integer.toString(region.x()))
                .resolve(region.z() + ".bin");
    }

    private RegionCoordinate regionFromPath(Path file) throws IOException {
        Path relative = originsDirectory.relativize(file);
        try {
            if (relative.getNameCount() == 3
                    && relative.getName(0).toString().equals("regions")) {
                return new RegionCoordinate(
                        parseCoordinate(relative.getName(1).toString()),
                        parseCoordinate(shardCoordinate(relative.getName(2).toString())));
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid origin shard path: " + file, invalid);
        }
        throw new IOException("Invalid origin shard path: " + file);
    }

    private HistoryKey keyFromPath(Path file) throws IOException {
        Path relative = originsDirectory.relativize(file);
        try {
            if (relative.getNameCount() == 4
                    && relative.getName(0).toString().equals("sections")) {
                requireSize(file, SECTION_ENTRY_BYTES);
                return new SectionKey(
                        parseCoordinate(relative.getName(1).toString()),
                        parseCoordinate(originCoordinate(relative.getName(3).toString())),
                        parseCoordinate(relative.getName(2).toString()));
            }
            if (relative.getNameCount() == 3
                    && relative.getName(0).toString().equals("entities")) {
                requireSize(file, ENTITY_ENTRY_BYTES);
                return new EntityChunkKey(
                        parseCoordinate(relative.getName(1).toString()),
                        parseCoordinate(originCoordinate(relative.getName(2).toString())));
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid origin path: " + file, invalid);
        }
        throw new IOException("Invalid origin path: " + file);
    }

    private static String originCoordinate(String filename) {
        return filename.substring(0, filename.length() - ".origin".length());
    }

    private static String shardCoordinate(String filename) {
        return filename.substring(0, filename.length() - ".bin".length());
    }

    private static void writeKey(DataOutputStream output, HistoryKey key)
            throws IOException {
        if (key instanceof SectionKey section) {
            output.writeByte(1);
            output.writeInt(section.chunkX());
            output.writeInt(section.sectionY());
            output.writeInt(section.chunkZ());
            return;
        }
        EntityChunkKey entities = (EntityChunkKey) key;
        output.writeByte(2);
        output.writeInt(entities.chunkX());
        output.writeInt(entities.chunkZ());
    }

    private static HistoryKey readKey(DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 1 -> new SectionKey(input.readInt(), input.readInt(), input.readInt());
            case 2 -> new EntityChunkKey(input.readInt(), input.readInt());
            default -> throw new IOException("Invalid origin key kind");
        };
    }

    private static ObjectId readId(DataInputStream input) throws IOException {
        byte[] id = input.readNBytes(32);
        if (id.length != 32) {
            throw new IOException("Truncated origin object ID");
        }
        return new ObjectId(HexFormat.of().formatHex(id));
    }

    private static void merge(
            Map<HistoryKey, ObjectId> target,
            Map<? extends HistoryKey, ObjectId> additions) throws IOException {
        for (var entry : additions.entrySet()) {
            ObjectId previous = target.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null) {
                requireSame(entry.getKey(), previous, entry.getValue());
            }
        }
    }

    private static void requireSame(
            HistoryKey key, ObjectId existing, ObjectId requested)
            throws OriginConflictException {
        if (!existing.equals(requested)) {
            throw new OriginConflictException("Origin is already fixed for " + key);
        }
    }

    private static RegionCoordinate region(HistoryKey key) {
        return new RegionCoordinate(
                Math.floorDiv(chunkX(key), REGION_SIZE),
                Math.floorDiv(chunkZ(key), REGION_SIZE));
    }

    private static int chunkX(HistoryKey key) {
        return key instanceof SectionKey section
                ? section.chunkX() : ((EntityChunkKey) key).chunkX();
    }

    private static int chunkZ(HistoryKey key) {
        return key instanceof SectionKey section
                ? section.chunkZ() : ((EntityChunkKey) key).chunkZ();
    }

    private static int parseCoordinate(String value) {
        int coordinate = Integer.parseInt(value);
        if (!Integer.toString(coordinate).equals(value)) {
            throw new IllegalArgumentException("Non-canonical coordinate");
        }
        return coordinate;
    }

    private static void requireSize(Path file, long expected) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != expected) {
            throw new IOException("Invalid origin entry size: " + file);
        }
    }

    private static long requireShardSize(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || attributes.size() < 4L * Integer.BYTES
                || attributes.size() > MAX_SHARD_BYTES) {
            throw new IOException("Invalid origin shard size: " + file);
        }
        return attributes.size();
    }

    private record OriginEntry(HistoryKey key, ObjectId id) { }

    private record CachedShard(long bytes, Map<HistoryKey, ObjectId> entries) { }

    private record RegionCoordinate(int x, int z)
            implements Comparable<RegionCoordinate> {
        @Override
        public int compareTo(RegionCoordinate other) {
            int xOrder = Integer.compare(x, other.x);
            return xOrder != 0 ? xOrder : Integer.compare(z, other.z);
        }
    }
}
