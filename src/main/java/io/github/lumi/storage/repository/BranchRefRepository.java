package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.List;

public final class BranchRefRepository {
    private static final int MAGIC = 0x4C524632;
    private static final int MAX_NAME_BYTES = 1024;
    private final Path headsDirectory;

    public BranchRefRepository(Path dimensionRepository) {
        headsDirectory = Objects.requireNonNull(dimensionRepository, "dimensionRepository")
                .resolve("refs").resolve("heads");
    }

    public synchronized BranchRef create(BranchName name, CommitId commit) throws IOException {
        Path path = path(name);
        if (Files.exists(path)) {
            throw new RefConflictException("Branch already exists: " + name);
        }
        BranchRef created = new BranchRef(name, commit, 0);
        AtomicFileWriter.replace(path, encode(created));
        return created;
    }

    public synchronized BranchRef compareAndSet(BranchRef expected, CommitId update) throws IOException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(update, "update");
        BranchRef current = read(expected.name()).orElseThrow(
                () -> new RefConflictException("Branch no longer exists: " + expected.name()));
        if (!current.equals(expected)) {
            throw new RefConflictException("Branch changed since operation started: " + expected.name());
        }
        BranchRef advanced = new BranchRef(expected.name(), update, expected.revision() + 1);
        AtomicFileWriter.replace(path(expected.name()), encode(advanced));
        return advanced;
    }

    public synchronized void delete(BranchRef expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        BranchRef current = read(expected.name()).orElseThrow(
                () -> new RefConflictException("Branch no longer exists: " + expected.name()));
        if (!current.equals(expected)) {
            throw new RefConflictException("Branch changed since operation started: " + expected.name());
        }
        Files.delete(path(expected.name()));
    }

    public synchronized Optional<BranchRef> read(BranchName name) throws IOException {
        Path path = path(name);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        BranchRef ref = decode(Files.readAllBytes(path));
        if (!ref.name().equals(name)) {
            throw new IOException("Ref filename and payload disagree: " + path);
        }
        return Optional.of(ref);
    }

    public synchronized List<BranchRef> list() throws IOException {
        if (!Files.exists(headsDirectory)) {
            return List.of();
        }
        try (var files = Files.list(headsDirectory)) {
            var refs = new java.util.ArrayList<BranchRef>();
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".ref")).toList()) {
                BranchRef ref = decode(Files.readAllBytes(file));
                if (!path(ref.name()).equals(file)) {
                    throw new IOException("Ref filename and payload disagree: " + file);
                }
                refs.add(ref);
            }
            return List.copyOf(refs);
        }
    }

    private byte[] encode(BranchRef ref) throws IOException {
        byte[] name = ref.name().value().getBytes(StandardCharsets.UTF_8);
        if (name.length > MAX_NAME_BYTES) {
            throw new IOException("Branch name is too large");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(name.length);
            output.write(name);
            output.write(HexFormat.of().parseHex(ref.commit().hex()));
            output.writeLong(ref.revision());
        }
        return bytes.toByteArray();
    }

    private BranchRef decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a Lumi V2 branch ref");
            }
            int nameLength = input.readInt();
            if (nameLength < 1 || nameLength > MAX_NAME_BYTES) {
                throw new IOException("Invalid branch name length");
            }
            byte[] name = input.readNBytes(nameLength);
            byte[] id = input.readNBytes(32);
            if (name.length != nameLength || id.length != 32) {
                throw new IOException("Truncated branch ref");
            }
            long revision = input.readLong();
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in branch ref");
            }
            return new BranchRef(
                    new BranchName(new String(name, StandardCharsets.UTF_8)),
                    new CommitId(new ObjectId(HexFormat.of().formatHex(id))),
                    revision);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid branch ref", invalid);
        }
    }

    private Path path(BranchName name) {
        String filename = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(name.value().getBytes(StandardCharsets.UTF_8));
        return headsDirectory.resolve(filename + ".ref");
    }
}
