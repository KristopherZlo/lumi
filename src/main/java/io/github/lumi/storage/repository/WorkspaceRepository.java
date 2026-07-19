package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.Workspace;
import io.github.lumi.domain.model.WorkspaceSettings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Atomic workspace metadata; all world objects remain in the dimension store. */
public final class WorkspaceRepository {
    private static final int MAGIC = 0x4C575332;
    private static final int MAX_NAME_BYTES = 4096;
    private final Path directory;

    public WorkspaceRepository(Path dimensionRepository) {
        directory = Objects.requireNonNull(dimensionRepository, "dimensionRepository")
                .resolve("workspaces");
    }

    public synchronized Workspace create(Workspace workspace) throws IOException {
        Objects.requireNonNull(workspace, "workspace");
        Path path = path(workspace.id());
        if (Files.exists(path)) {
            throw new RefConflictException("Workspace already exists: " + workspace.id());
        }
        AtomicFileWriter.replace(path, encode(workspace));
        return workspace;
    }

    public synchronized Workspace replace(Workspace expected, Workspace update)
            throws IOException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(update, "update");
        if (!expected.id().equals(update.id())) {
            throw new IllegalArgumentException("Workspace identity cannot change");
        }
        Workspace current = read(expected.id()).orElseThrow(
                () -> new RefConflictException("Workspace no longer exists: " + expected.id()));
        if (!current.equals(expected)) {
            throw new RefConflictException("Workspace changed since it was read");
        }
        AtomicFileWriter.replace(path(update.id()), encode(update));
        return update;
    }

    public synchronized Optional<Workspace> read(UUID id) throws IOException {
        Path path = path(Objects.requireNonNull(id, "id"));
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        Workspace workspace = decode(Files.readAllBytes(path));
        if (!workspace.id().equals(id)) {
            throw new IOException("Workspace filename and payload disagree: " + path);
        }
        return Optional.of(workspace);
    }

    public synchronized List<Workspace> list() throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        List<Workspace> workspaces = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".workspace"))
                    .toList()) {
                workspaces.add(decode(Files.readAllBytes(file)));
            }
        }
        workspaces.sort(Comparator.comparing(workspace -> workspace.id().toString()));
        return List.copyOf(workspaces);
    }

    private Path path(UUID id) {
        return directory.resolve(id + ".workspace");
    }

    private static byte[] encode(Workspace workspace) throws IOException {
        byte[] name = workspace.name().getBytes(StandardCharsets.UTF_8);
        if (name.length > MAX_NAME_BYTES) {
            throw new IOException("Workspace name is too large");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeLong(workspace.id().getMostSignificantBits());
            output.writeLong(workspace.id().getLeastSignificantBits());
            output.writeInt(name.length);
            output.write(name);
            output.writeBoolean(workspace.bounds().isPresent());
            if (workspace.bounds().isPresent()) {
                writeBounds(output, workspace.bounds().orElseThrow());
            }
            output.writeBoolean(workspace.settings().hideZoneCommits());
            output.writeBoolean(workspace.settings().includeEntitiesOnRestore());
            output.writeBoolean(workspace.settings().previewGenerationEnabled());
            output.writeBoolean(workspace.settings().workspaceHudEnabled());
        }
        return bytes.toByteArray();
    }

    private static Workspace decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a Lumi V2 workspace");
            }
            UUID id = new UUID(input.readLong(), input.readLong());
            int nameLength = input.readInt();
            if (nameLength < 1 || nameLength > MAX_NAME_BYTES) {
                throw new IOException("Invalid workspace name length");
            }
            byte[] name = input.readNBytes(nameLength);
            if (name.length != nameLength) {
                throw new IOException("Truncated workspace name");
            }
            int bounded = readFlag(input, "workspace bounds");
            Optional<BlockBox> bounds = bounded == 1
                    ? Optional.of(readBounds(input)) : Optional.empty();
            boolean hideZoneCommits = readFlag(input, "hide-zone-commits setting") == 1;
            boolean restoreEntities = readFlag(input, "restore-entities setting") == 1;
            if (input.available() != 0 && input.available() != 2) {
                throw new IOException("Invalid workspace settings extension");
            }
            WorkspaceSettings settings = input.available() == 0
                    ? new WorkspaceSettings(hideZoneCommits, restoreEntities)
                    : new WorkspaceSettings(
                            hideZoneCommits, restoreEntities,
                            readFlag(input, "preview-generation setting") == 1,
                            readFlag(input, "workspace-hud setting") == 1);
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in workspace");
            }
            return new Workspace(id, new String(name, StandardCharsets.UTF_8), bounds, settings);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid workspace payload", invalid);
        }
    }

    private static int readFlag(DataInputStream input, String label) throws IOException {
        int flag = input.readUnsignedByte();
        if (flag > 1) {
            throw new IOException("Invalid " + label + " flag");
        }
        return flag;
    }

    private static void writeBounds(DataOutputStream output, BlockBox bounds) throws IOException {
        output.writeInt(bounds.minX());
        output.writeInt(bounds.minY());
        output.writeInt(bounds.minZ());
        output.writeInt(bounds.maxX());
        output.writeInt(bounds.maxY());
        output.writeInt(bounds.maxZ());
    }

    private static BlockBox readBounds(DataInputStream input) throws IOException {
        return new BlockBox(
                input.readInt(), input.readInt(), input.readInt(),
                input.readInt(), input.readInt(), input.readInt());
    }
}
