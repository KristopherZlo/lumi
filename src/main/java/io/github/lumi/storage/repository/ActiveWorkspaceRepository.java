package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.ActiveWorkspace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Atomic revisioned selector kept separate from immutable workspace metadata. */
public final class ActiveWorkspaceRepository {
    private static final int MAGIC = 0x4C574132;
    private static final int FILE_BYTES = Integer.BYTES + 3 * Long.BYTES;
    private final Path pointer;

    public ActiveWorkspaceRepository(Path dimensionRepository) {
        pointer = Objects.requireNonNull(dimensionRepository, "dimensionRepository")
                .resolve("workspaces").resolve("active.bin");
    }

    public synchronized ActiveWorkspace create(UUID id) throws IOException {
        Objects.requireNonNull(id, "id");
        if (Files.exists(pointer)) {
            throw new RefConflictException("Active workspace already exists");
        }
        ActiveWorkspace created = new ActiveWorkspace(id, 0);
        AtomicFileWriter.replace(pointer, encode(created));
        return created;
    }

    public synchronized ActiveWorkspace compareAndSet(
            ActiveWorkspace expected, UUID update) throws IOException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(update, "update");
        ActiveWorkspace current = read().orElseThrow(
                () -> new RefConflictException("Active workspace is missing"));
        if (!current.equals(expected)) {
            throw new RefConflictException("Active workspace changed since operation started");
        }
        ActiveWorkspace advanced = new ActiveWorkspace(
                update, Math.addExact(expected.revision(), 1));
        AtomicFileWriter.replace(pointer, encode(advanced));
        return advanced;
    }

    public synchronized Optional<ActiveWorkspace> read() throws IOException {
        return Files.exists(pointer)
                ? Optional.of(decode(RepositoryFileReader.read(pointer, FILE_BYTES)))
                : Optional.empty();
    }

    private static byte[] encode(ActiveWorkspace workspace) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeLong(workspace.id().getMostSignificantBits());
            output.writeLong(workspace.id().getLeastSignificantBits());
            output.writeLong(workspace.revision());
        }
        return bytes.toByteArray();
    }

    private static ActiveWorkspace decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a Lumi V2 active workspace pointer");
            }
            ActiveWorkspace workspace = new ActiveWorkspace(
                    new UUID(input.readLong(), input.readLong()), input.readLong());
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in active workspace pointer");
            }
            return workspace;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid active workspace pointer", invalid);
        }
    }
}
