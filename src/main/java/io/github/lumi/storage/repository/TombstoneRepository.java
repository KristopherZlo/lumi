package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitTombstone;
import io.github.lumi.domain.model.ObjectId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Atomic durable soft-delete markers keyed by immutable commit identity. */
public final class TombstoneRepository {
    private static final int MAGIC = 0x4C544D32;
    private static final int MAX_NAME_BYTES = 4096;
    private static final int MAX_FILE_BYTES =
            Integer.BYTES + 32 + 2 * Long.BYTES + Integer.BYTES
                    + MAX_NAME_BYTES + Long.BYTES + Integer.BYTES;
    private final Path directory;

    public TombstoneRepository(Path dimensionRepository) {
        directory = Objects.requireNonNull(dimensionRepository, "dimensionRepository")
                .resolve("tombstones");
    }

    public synchronized CommitTombstone create(CommitTombstone tombstone)
            throws IOException {
        Objects.requireNonNull(tombstone, "tombstone");
        Optional<CommitTombstone> existing = read(tombstone.commit());
        if (existing.isPresent()) {
            if (existing.orElseThrow().equals(tombstone)) {
                return tombstone;
            }
            throw new RefConflictException(
                    "Commit tombstone already exists: " + tombstone.commit());
        }
        AtomicFileWriter.replace(path(tombstone.commit()), encode(tombstone));
        return tombstone;
    }

    public synchronized Optional<CommitTombstone> read(CommitId commit)
            throws IOException {
        Path file = path(Objects.requireNonNull(commit, "commit"));
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        CommitTombstone tombstone = decode(
                RepositoryFileReader.read(file, MAX_FILE_BYTES));
        if (!tombstone.commit().equals(commit)) {
            throw new IOException("Tombstone filename and payload disagree: " + file);
        }
        return Optional.of(tombstone);
    }

    public synchronized boolean contains(CommitId commit) {
        return Files.exists(path(Objects.requireNonNull(commit, "commit")));
    }

    public synchronized List<CommitTombstone> list() throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        ArrayList<CommitTombstone> tombstones = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".tomb"))
                    .toList()) {
                CommitTombstone tombstone = decode(
                        RepositoryFileReader.read(file, MAX_FILE_BYTES));
                if (!path(tombstone.commit()).equals(file)) {
                    throw new IOException("Tombstone filename and payload disagree: " + file);
                }
                tombstones.add(tombstone);
            }
        }
        tombstones.sort(Comparator.comparing(value -> value.commit().hex()));
        return List.copyOf(tombstones);
    }

    public synchronized void delete(CommitId commit) throws IOException {
        Files.deleteIfExists(path(Objects.requireNonNull(commit, "commit")));
    }

    private static byte[] encode(CommitTombstone tombstone) throws IOException {
        byte[] name = tombstone.deletedBy().name().getBytes(StandardCharsets.UTF_8);
        if (name.length > MAX_NAME_BYTES) {
            throw new IOException("Tombstone author name is too large");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.write(HexFormat.of().parseHex(tombstone.commit().hex()));
            UUID author = tombstone.deletedBy().id();
            output.writeLong(author.getMostSignificantBits());
            output.writeLong(author.getLeastSignificantBits());
            output.writeInt(name.length);
            output.write(name);
            output.writeLong(tombstone.deletedAt().getEpochSecond());
            output.writeInt(tombstone.deletedAt().getNano());
        }
        return bytes.toByteArray();
    }

    private static CommitTombstone decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a Lumi V2 tombstone");
            }
            byte[] commit = input.readNBytes(32);
            long most = input.readLong();
            long least = input.readLong();
            int nameLength = input.readInt();
            if (commit.length != 32 || nameLength < 1 || nameLength > MAX_NAME_BYTES) {
                throw new IOException("Invalid tombstone metadata");
            }
            byte[] name = input.readNBytes(nameLength);
            long second = input.readLong();
            int nano = input.readInt();
            if (name.length != nameLength || input.available() != 0) {
                throw new IOException("Truncated or trailing tombstone bytes");
            }
            return new CommitTombstone(
                    new CommitId(new ObjectId(HexFormat.of().formatHex(commit))),
                    new CommitAuthor(new UUID(most, least),
                            new String(name, StandardCharsets.UTF_8)),
                    Instant.ofEpochSecond(second, nano));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid tombstone", invalid);
        }
    }

    private Path path(CommitId commit) {
        return directory.resolve(commit.hex() + ".tomb");
    }
}
