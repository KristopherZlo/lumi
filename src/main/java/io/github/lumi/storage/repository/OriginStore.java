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
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class OriginStore {
    private static final int MAGIC = 0x4C4F5232;
    private static final long SECTION_ENTRY_BYTES = Integer.BYTES + 1L
            + 3L * Integer.BYTES + 32L;
    private static final long ENTITY_ENTRY_BYTES = Integer.BYTES + 1L
            + 2L * Integer.BYTES + 32L;
    private final Path originsDirectory;

    public OriginStore(Path dimensionRepository) {
        originsDirectory = Objects.requireNonNull(dimensionRepository, "dimensionRepository").resolve("origins");
    }

    public synchronized boolean register(HistoryKey key, ObjectId origin) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(origin, "origin");
        Optional<ObjectId> existing = read(key);
        if (existing.isPresent()) {
            if (!existing.orElseThrow().equals(origin)) {
                throw new OriginConflictException("Origin is already fixed for " + key);
            }
            return false;
        }
        if (!AtomicFileWriter.createOnce(path(key), encode(key, origin))) {
            return register(key, origin);
        }
        return true;
    }

    public synchronized Optional<ObjectId> read(HistoryKey key) throws IOException {
        Objects.requireNonNull(key, "key");
        Path path = path(key);
        return Files.exists(path) ? Optional.of(decode(key, Files.readAllBytes(path))) : Optional.empty();
    }

    public synchronized Set<ObjectId> allOrigins() throws IOException {
        return Set.copyOf(entries().values());
    }

    /**
     * Reads origin membership from canonical paths without loading immutable
     * payloads. Payload readers still validate the stored key and object ID.
     */
    public synchronized Set<HistoryKey> keys() throws IOException {
        if (!Files.exists(originsDirectory)) {
            return Set.of();
        }
        Set<HistoryKey> keys = new HashSet<>();
        try (var files = Files.walk(originsDirectory)) {
            for (Path file : files
                    .filter(path -> path.getFileName().toString().endsWith(".origin"))
                    .toList()) {
                HistoryKey key = keyFromPath(file);
                if (!keys.add(key)) {
                    throw new IOException("Duplicate origin entry for " + key);
                }
            }
        }
        return Set.copyOf(keys);
    }

    public synchronized Map<HistoryKey, ObjectId> entries() throws IOException {
        return entries(originsDirectory);
    }

    public synchronized Map<EntityChunkKey, ObjectId> entityEntries()
            throws IOException {
        Map<EntityChunkKey, ObjectId> entities = new HashMap<>();
        for (var entry : entries(originsDirectory.resolve("entities")).entrySet()) {
            if (!(entry.getKey() instanceof EntityChunkKey key)) {
                throw new IOException("Non-entity origin in entity directory");
            }
            entities.put(key, entry.getValue());
        }
        return Map.copyOf(entities);
    }

    private Map<HistoryKey, ObjectId> entries(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return Map.of();
        }
        Map<HistoryKey, ObjectId> origins = new HashMap<>();
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".origin")).toList()) {
                OriginEntry entry = decode(Files.readAllBytes(file));
                if (origins.put(entry.key(), entry.id()) != null) {
                    throw new IOException("Duplicate origin entry for " + entry.key());
                }
            }
        }
        return Map.copyOf(origins);
    }

    private byte[] encode(HistoryKey key, ObjectId origin) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            if (key instanceof SectionKey section) {
                output.writeByte(1);
                output.writeInt(section.chunkX());
                output.writeInt(section.sectionY());
                output.writeInt(section.chunkZ());
            } else if (key instanceof EntityChunkKey entities) {
                output.writeByte(2);
                output.writeInt(entities.chunkX());
                output.writeInt(entities.chunkZ());
            }
            output.write(HexFormat.of().parseHex(origin.hex()));
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
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a Lumi V2 origin entry");
            }
            int kind = input.readUnsignedByte();
            HistoryKey stored = switch (kind) {
                case 1 -> new SectionKey(input.readInt(), input.readInt(), input.readInt());
                case 2 -> new EntityChunkKey(input.readInt(), input.readInt());
                default -> throw new IOException("Invalid origin key kind");
            };
            byte[] id = input.readNBytes(32);
            if (id.length != 32 || input.available() != 0) {
                throw new IOException("Invalid origin entry for " + stored);
            }
            return new OriginEntry(stored, new ObjectId(HexFormat.of().formatHex(id)));
        }
    }

    private Path path(HistoryKey key) {
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

    private record OriginEntry(HistoryKey key, ObjectId id) { }
}
