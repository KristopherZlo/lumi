package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.ActiveBranch;
import io.github.lumi.domain.model.BranchName;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Atomically persists the revisioned active branch pointer for one dimension. */
public final class ActiveBranchRepository {
    private static final int MAGIC = 0x4C414232;
    private static final int MAX_NAME_BYTES = 1024;
    private static final int MAX_FILE_BYTES =
            2 * Integer.BYTES + MAX_NAME_BYTES + Long.BYTES;
    private final Path pointerFile;

    public ActiveBranchRepository(Path dimensionRepository) {
        pointerFile = Objects.requireNonNull(dimensionRepository, "dimensionRepository")
                .resolve("refs").resolve("active.bin");
    }

    public synchronized ActiveBranch create(BranchName name) throws IOException {
        Objects.requireNonNull(name, "name");
        if (Files.exists(pointerFile)) {
            throw new RefConflictException("Active branch already exists");
        }
        ActiveBranch created = new ActiveBranch(name, 0);
        AtomicFileWriter.replace(pointerFile, encode(created));
        return created;
    }

    public synchronized ActiveBranch compareAndSet(
            ActiveBranch expected, BranchName update) throws IOException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(update, "update");
        ActiveBranch current = read().orElseThrow(
                () -> new RefConflictException("Active branch is missing"));
        if (!current.equals(expected)) {
            throw new RefConflictException("Active branch changed since operation started");
        }
        ActiveBranch advanced = new ActiveBranch(update, expected.revision() + 1);
        AtomicFileWriter.replace(pointerFile, encode(advanced));
        return advanced;
    }

    public synchronized Optional<ActiveBranch> read() throws IOException {
        return Files.exists(pointerFile)
                ? Optional.of(decode(RepositoryFileReader.read(
                        pointerFile, MAX_FILE_BYTES)))
                : Optional.empty();
    }

    private static byte[] encode(ActiveBranch branch) throws IOException {
        byte[] name = branch.name().value().getBytes(StandardCharsets.UTF_8);
        if (name.length > MAX_NAME_BYTES) {
            throw new IOException("Active branch name is too large");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(name.length);
            output.write(name);
            output.writeLong(branch.revision());
        }
        return bytes.toByteArray();
    }

    private static ActiveBranch decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a Lumi V2 active branch pointer");
            }
            int nameLength = input.readInt();
            if (nameLength < 1 || nameLength > MAX_NAME_BYTES) {
                throw new IOException("Invalid active branch name length");
            }
            byte[] name = input.readNBytes(nameLength);
            if (name.length != nameLength) {
                throw new IOException("Truncated active branch pointer");
            }
            ActiveBranch branch = new ActiveBranch(
                    new BranchName(new String(name, StandardCharsets.UTF_8)), input.readLong());
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in active branch pointer");
            }
            return branch;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid active branch pointer", invalid);
        }
    }
}
