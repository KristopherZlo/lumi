package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.Zone;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Atomic binary persistence for zone metadata inside one shared repository. */
public final class ZoneRepository {
    private static final int MAGIC = 0x4C5A4E32;
    private static final int MAX_NAME_BYTES = 1_024;
    private static final int MAX_CELLS = 1_000_000;
    private static final int MAX_ACTORS = 10_000;
    private static final Comparator<SectionKey> CELL_ORDER = Comparator
            .comparingInt(SectionKey::chunkX)
            .thenComparingInt(SectionKey::chunkZ)
            .thenComparingInt(SectionKey::sectionY);
    private final Path root;

    public ZoneRepository(Path dimensionRepository) {
        root = Objects.requireNonNull(dimensionRepository, "dimensionRepository")
                .resolve("workspaces");
    }

    public synchronized Zone create(Zone zone) throws IOException {
        Path file = file(zone.workspaceId(), zone.id());
        if (Files.exists(file)) {
            throw new RefConflictException("Zone already exists: " + zone.id());
        }
        AtomicFileWriter.replace(file, encode(zone));
        return zone;
    }

    public synchronized Zone replace(Zone expected, Zone update) throws IOException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(update, "update");
        if (!expected.id().equals(update.id())
                || !expected.workspaceId().equals(update.workspaceId())) {
            throw new IllegalArgumentException("Zone identity cannot change");
        }
        if (update.revision() != Math.addExact(expected.revision(), 1)) {
            throw new IllegalArgumentException("Zone replacement must advance its revision once");
        }
        Zone current = read(expected.workspaceId(), expected.id()).orElseThrow(
                () -> new RefConflictException("Zone no longer exists: " + expected.id()));
        if (!current.equals(expected)) {
            throw new RefConflictException("Zone changed since it was read");
        }
        AtomicFileWriter.replace(file(update.workspaceId(), update.id()), encode(update));
        return update;
    }

    public synchronized Optional<Zone> read(UUID workspaceId, UUID zoneId) throws IOException {
        Path file = file(workspaceId, zoneId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Zone zone = decode(Files.readAllBytes(file));
        if (!zone.workspaceId().equals(workspaceId) || !zone.id().equals(zoneId)) {
            throw new IOException("Zone file identity does not match its path");
        }
        return Optional.of(zone);
    }

    public synchronized List<Zone> list(UUID workspaceId) throws IOException {
        Path directory = zoneDirectory(workspaceId);
        if (!Files.exists(directory)) {
            return List.of();
        }
        ArrayList<Zone> zones = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*.zone")) {
            for (Path file : files) {
                zones.add(decode(Files.readAllBytes(file)));
            }
        }
        if (zones.stream().anyMatch(zone -> !zone.workspaceId().equals(workspaceId))) {
            throw new IOException("Zone directory contains another workspace");
        }
        zones.sort(Comparator.comparing(Zone::id));
        return List.copyOf(zones);
    }

    private byte[] encode(Zone zone) throws IOException {
        byte[] name = zone.name().getBytes(StandardCharsets.UTF_8);
        if (name.length > MAX_NAME_BYTES || zone.cells().size() > MAX_CELLS
                || zone.activeActors().size() > MAX_ACTORS) {
            throw new IOException("Zone metadata exceeds storage limits");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            writeUuid(output, zone.id());
            writeUuid(output, zone.workspaceId());
            output.writeInt(name.length);
            output.write(name);
            output.writeInt(zone.color());
            var cells = new ArrayList<>(zone.cells());
            cells.sort(CELL_ORDER);
            output.writeInt(cells.size());
            for (SectionKey cell : cells) {
                output.writeInt(cell.chunkX());
                output.writeInt(cell.sectionY());
                output.writeInt(cell.chunkZ());
            }
            var actors = new ArrayList<>(zone.activeActors());
            actors.sort(Comparator.naturalOrder());
            output.writeInt(actors.size());
            for (UUID actor : actors) writeUuid(output, actor);
            output.writeLong(zone.revision());
        }
        return bytes.toByteArray();
    }

    private Zone decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) throw new IOException("Not a Lumi V2 zone");
            UUID id = readUuid(input);
            UUID workspace = readUuid(input);
            int nameLength = boundedCount(input.readInt(), MAX_NAME_BYTES, "zone name");
            byte[] name = input.readNBytes(nameLength);
            if (name.length != nameLength) throw new IOException("Truncated zone name");
            int color = input.readInt();
            int cellCount = boundedCount(input.readInt(), MAX_CELLS, "zone cells");
            Set<SectionKey> cells = new HashSet<>();
            SectionKey previousCell = null;
            for (int index = 0; index < cellCount; index++) {
                SectionKey cell = new SectionKey(
                        input.readInt(), input.readInt(), input.readInt());
                if (previousCell != null && CELL_ORDER.compare(previousCell, cell) >= 0) {
                    throw new IOException("Zone cells are not canonical");
                }
                cells.add(cell);
                previousCell = cell;
            }
            int actorCount = boundedCount(input.readInt(), MAX_ACTORS, "zone actors");
            Set<UUID> actors = new HashSet<>();
            UUID previousActor = null;
            for (int index = 0; index < actorCount; index++) {
                UUID actor = readUuid(input);
                if (previousActor != null && previousActor.compareTo(actor) >= 0) {
                    throw new IOException("Zone actors are not canonical");
                }
                actors.add(actor);
                previousActor = actor;
            }
            long revision = input.available() == 0 ? 0 : input.readLong();
            if (input.available() != 0) {
                throw new IOException("Zone metadata is not canonical");
            }
            return new Zone(id, workspace, new String(name, StandardCharsets.UTF_8),
                    color, cells, actors, revision);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid zone metadata", invalid);
        }
    }

    private Path file(UUID workspace, UUID zone) {
        return zoneDirectory(workspace).resolve(zone + ".zone");
    }

    private Path zoneDirectory(UUID workspace) {
        return root.resolve(Objects.requireNonNull(workspace, "workspace").toString())
                .resolve("zones");
    }

    private static int boundedCount(int value, int maximum, String field) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("Invalid " + field + " count");
        return value;
    }

    private static void writeUuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }
}
