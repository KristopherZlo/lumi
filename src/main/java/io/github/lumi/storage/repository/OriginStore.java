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
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

public final class OriginStore {
    private static final int MAGIC = 0x4C4F5232;
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
            if (!stored.equals(expected) || id.length != 32 || input.available() != 0) {
                throw new IOException("Invalid origin entry for " + expected);
            }
            return new ObjectId(HexFormat.of().formatHex(id));
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
}
