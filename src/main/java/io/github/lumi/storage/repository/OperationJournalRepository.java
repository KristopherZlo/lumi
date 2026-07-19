package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchSwitchTarget;
import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.model.WorkspaceSwitchTarget;
import io.github.lumi.domain.model.ZoneRestoreTarget;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OperationJournalRepository {
    private static final int MAGIC = 0x4C4F4A32;
    private static final int GENERATIONS_MAGIC = 0x4C4F4731;
    private static final int MAX_NAME_BYTES = 1024;
    private static final int MAX_GENERATION_KEYS = 1_000_000;
    private static final Comparator<HistoryKey> KEY_ORDER = Comparator
            .comparingInt(OperationJournalRepository::keyKind)
            .thenComparingInt(OperationJournalRepository::keyChunkX)
            .thenComparingInt(OperationJournalRepository::keyChunkZ)
            .thenComparingInt(OperationJournalRepository::keySectionY);
    private static final Logger LOGGER =
            Logger.getLogger(OperationJournalRepository.class.getName());
    private final Path journalFile;
    private final Path generationsFile;

    public OperationJournalRepository(Path dimensionRepository) {
        Path operations = Objects.requireNonNull(dimensionRepository, "dimensionRepository")
                .resolve("operations");
        journalFile = operations.resolve("active.bin");
        generationsFile = operations.resolve("active-generations.bin");
    }

    public synchronized OperationJournal create(OperationJournal journal) throws IOException {
        Objects.requireNonNull(journal, "journal");
        if (Files.exists(journalFile)) {
            throw new JournalConflictException("A dimension operation journal is already active");
        }
        if (journal.capturedGenerations().isPresent()) {
            AtomicFileWriter.replace(generationsFile, encodeGenerations(
                    journal.operationId(), journal.capturedGenerations().orElseThrow()));
        }
        AtomicFileWriter.replace(journalFile, encode(journal));
        return journal;
    }

    public synchronized OperationJournal advance(OperationJournal expected, OperationPhase next) throws IOException {
        OperationJournal current = readExpected(expected);
        if (!current.equals(expected)) {
            throw new JournalConflictException("Active operation journal changed");
        }
        OperationJournal advanced = current.withPhase(next);
        AtomicFileWriter.replace(journalFile, encode(advanced));
        return advanced;
    }

    public synchronized void clear(OperationJournal expected) throws IOException {
        if (!readExpected(expected).equals(expected)) {
            throw new JournalConflictException("Active operation journal changed");
        }
        Files.delete(journalFile);
        try {
            Files.deleteIfExists(generationsFile);
        } catch (IOException failed) {
            LOGGER.log(Level.WARNING,
                    "Could not clean up Lumi operation generation boundary", failed);
        }
    }

    public synchronized Optional<OperationJournal> read() throws IOException {
        return Files.exists(journalFile)
                ? Optional.of(decode(Files.readAllBytes(journalFile)))
                : Optional.empty();
    }

    private byte[] encode(OperationJournal journal) throws IOException {
        byte[] branch = journal.target().branch().value().getBytes(StandardCharsets.UTF_8);
        if (branch.length > MAX_NAME_BYTES) {
            throw new IOException("Journal branch name is too large");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            writeUuid(output, journal.operationId());
            output.writeByte(journal.kind().code());
            output.writeByte(journal.phase().code());
            output.writeInt(branch.length);
            output.write(branch);
            writeId(output, journal.target().expectedHead());
            output.writeLong(journal.target().expectedRevision());
            writeOptionalId(output, journal.target().target());
            writeOptionalId(output, journal.target().returnPoint());
            writeBranchSwitch(output, journal.target().branchSwitch());
            writeBlockArea(output, journal.target().blockArea());
            output.writeBoolean(journal.target().excludeEntities());
            writeWorkspaceSwitch(output, journal.target().workspaceSwitch());
            writeZoneRestore(output, journal.target().zoneRestore());
            output.writeBoolean(journal.capturedGenerations().isPresent());
        }
        return bytes.toByteArray();
    }

    private OperationJournal decode(byte[] payload) throws IOException {
        return decode(payload, null);
    }

    private OperationJournal decode(
            byte[] payload, WorkingIndexSnapshot knownGenerations) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a Lumi V2 operation journal");
            }
            UUID operationId = readUuid(input);
            OperationKind kind = OperationKind.fromCode(input.readUnsignedByte());
            OperationPhase phase = OperationPhase.fromCode(input.readUnsignedByte());
            int branchLength = input.readInt();
            if (branchLength < 1 || branchLength > MAX_NAME_BYTES) {
                throw new IOException("Invalid journal branch name length");
            }
            byte[] branch = input.readNBytes(branchLength);
            if (branch.length != branchLength) {
                throw new IOException("Truncated operation journal");
            }
            var branchName = new BranchName(new String(branch, StandardCharsets.UTF_8));
            CommitId expected = readId(input);
            long revision = input.readLong();
            var targetCommit = readOptionalId(input);
            var returnPoint = readOptionalId(input);
            var branchSwitch = readBranchSwitch(input);
            var blockArea = readBlockArea(input);
            boolean excludeEntities = false;
            if (input.available() != 0) {
                int excluded = input.readUnsignedByte();
                if (excluded > 1) {
                    throw new IOException("Invalid entity exclusion flag");
                }
                excludeEntities = excluded == 1;
            }
            var workspaceSwitch = input.available() == 0
                    ? Optional.<WorkspaceSwitchTarget>empty() : readWorkspaceSwitch(input);
            var zoneRestore = input.available() == 0
                    ? Optional.<ZoneRestoreTarget>empty() : readZoneRestore(input);
            Optional<WorkingIndexSnapshot> capturedGenerations = Optional.empty();
            if (input.available() != 0) {
                int present = input.readUnsignedByte();
                if (present > 1) {
                    throw new IOException("Invalid captured generation boundary flag");
                }
                if (present == 1) {
                    capturedGenerations = Optional.of(knownGenerations == null
                            ? readGenerations(operationId) : knownGenerations);
                }
            }
            OperationTarget target = new OperationTarget(
                    branchName, expected, revision, targetCommit, returnPoint,
                    branchSwitch, blockArea, excludeEntities, workspaceSwitch, zoneRestore);
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in operation journal");
            }
            return new OperationJournal(
                    operationId, kind, phase, target, capturedGenerations);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid operation journal", invalid);
        }
    }

    private OperationJournal readExpected(OperationJournal expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        if (!Files.exists(journalFile)) {
            throw new JournalConflictException("Active operation journal is missing");
        }
        return decode(Files.readAllBytes(journalFile),
                expected.capturedGenerations().orElse(null));
    }

    private byte[] encodeGenerations(
            UUID operationId, WorkingIndexSnapshot snapshot) throws IOException {
        if (snapshot.generations().size() > MAX_GENERATION_KEYS) {
            throw new IOException(
                    "Operation generation boundary exceeds " + MAX_GENERATION_KEYS + " keys");
        }
        var keys = new ArrayList<>(snapshot.generations().keySet());
        keys.sort(KEY_ORDER);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(GENERATIONS_MAGIC);
            writeUuid(output, operationId);
            output.writeInt(keys.size());
            for (HistoryKey key : keys) {
                writeHistoryKey(output, key);
                output.writeLong(snapshot.generations().get(key));
            }
        }
        return bytes.toByteArray();
    }

    private WorkingIndexSnapshot readGenerations(UUID expectedOperationId) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(generationsFile))) {
            if (input.readInt() != GENERATIONS_MAGIC) {
                throw new IOException("Not a Lumi operation generation boundary");
            }
            if (!readUuid(input).equals(expectedOperationId)) {
                throw new IOException("Operation generation boundary belongs to another operation");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_GENERATION_KEYS) {
                throw new IOException("Invalid operation generation boundary size");
            }
            Map<HistoryKey, Long> generations = new LinkedHashMap<>();
            HistoryKey previous = null;
            for (int index = 0; index < count; index++) {
                HistoryKey key = readHistoryKey(input);
                long generation = input.readLong();
                if (generation < 1 || (previous != null && KEY_ORDER.compare(previous, key) >= 0)) {
                    throw new IOException("Operation generation boundary is not canonical");
                }
                previous = key;
                generations.put(key, generation);
            }
            if (input.read() != -1) {
                throw new IOException("Trailing bytes in operation generation boundary");
            }
            return new WorkingIndexSnapshot(generations);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid operation generation boundary", invalid);
        }
    }

    private static void writeOptionalId(DataOutputStream output, Optional<CommitId> id) throws IOException {
        output.writeBoolean(id.isPresent());
        if (id.isPresent()) {
            writeId(output, id.orElseThrow());
        }
    }

    private static Optional<CommitId> readOptionalId(DataInputStream input) throws IOException {
        int present = input.readUnsignedByte();
        if (present > 1) {
            throw new IOException("Invalid optional commit ID flag");
        }
        return present == 1 ? Optional.of(readId(input)) : Optional.empty();
    }

    private static void writeBranchSwitch(
            DataOutputStream output, Optional<BranchSwitchTarget> branchSwitch) throws IOException {
        output.writeBoolean(branchSwitch.isPresent());
        if (branchSwitch.isEmpty()) return;
        BranchSwitchTarget target = branchSwitch.orElseThrow();
        byte[] name = target.branch().value().getBytes(StandardCharsets.UTF_8);
        if (name.length > MAX_NAME_BYTES) {
            throw new IOException("Branch switch target name is too large");
        }
        output.writeInt(name.length);
        output.write(name);
        output.writeLong(target.targetRevision());
        output.writeLong(target.expectedActiveRevision());
    }

    private static Optional<BranchSwitchTarget> readBranchSwitch(
            DataInputStream input) throws IOException {
        int present = input.readUnsignedByte();
        if (present > 1) {
            throw new IOException("Invalid branch switch target flag");
        }
        if (present == 0) return Optional.empty();
        int nameLength = input.readInt();
        if (nameLength < 1 || nameLength > MAX_NAME_BYTES) {
            throw new IOException("Invalid branch switch target name length");
        }
        byte[] name = input.readNBytes(nameLength);
        if (name.length != nameLength) {
            throw new IOException("Truncated branch switch target");
        }
        return Optional.of(new BranchSwitchTarget(
                new BranchName(new String(name, StandardCharsets.UTF_8)),
                input.readLong(), input.readLong()));
    }

    private static void writeBlockArea(
            DataOutputStream output, Optional<BlockAreaTarget> blockArea) throws IOException {
        output.writeBoolean(blockArea.isPresent());
        if (blockArea.isEmpty()) return;
        BlockAreaTarget target = blockArea.orElseThrow();
        BlockBox area = target.area();
        output.writeInt(area.minX());
        output.writeInt(area.minY());
        output.writeInt(area.minZ());
        output.writeInt(area.maxX());
        output.writeInt(area.maxY());
        output.writeInt(area.maxZ());
        output.writeBoolean(target.outside());
    }

    private static Optional<BlockAreaTarget> readBlockArea(DataInputStream input) throws IOException {
        int present = input.readUnsignedByte();
        if (present > 1) {
            throw new IOException("Invalid partial Restore area flag");
        }
        if (present == 0) return Optional.empty();
        BlockBox area = new BlockBox(
                input.readInt(), input.readInt(), input.readInt(),
                input.readInt(), input.readInt(), input.readInt());
        int outside = input.readUnsignedByte();
        if (outside > 1) {
            throw new IOException("Invalid outside Restore flag");
        }
        return Optional.of(new BlockAreaTarget(area, outside == 1));
    }

    private static void writeWorkspaceSwitch(
            DataOutputStream output,
            Optional<WorkspaceSwitchTarget> workspaceSwitch) throws IOException {
        output.writeBoolean(workspaceSwitch.isPresent());
        if (workspaceSwitch.isEmpty()) return;
        WorkspaceSwitchTarget target = workspaceSwitch.orElseThrow();
        writeUuid(output, target.expectedWorkspace());
        writeUuid(output, target.targetWorkspace());
        output.writeLong(target.expectedRevision());
    }

    private static Optional<WorkspaceSwitchTarget> readWorkspaceSwitch(
            DataInputStream input) throws IOException {
        int present = input.readUnsignedByte();
        if (present > 1) {
            throw new IOException("Invalid workspace switch target flag");
        }
        return present == 0 ? Optional.empty() : Optional.of(new WorkspaceSwitchTarget(
                readUuid(input), readUuid(input), input.readLong()));
    }

    private static void writeZoneRestore(
            DataOutputStream output, Optional<ZoneRestoreTarget> zoneRestore) throws IOException {
        output.writeBoolean(zoneRestore.isPresent());
        if (zoneRestore.isEmpty()) return;
        ZoneRestoreTarget target = zoneRestore.orElseThrow();
        writeUuid(output, target.workspaceId());
        writeUuid(output, target.zoneId());
        output.writeLong(target.revision());
    }

    private static Optional<ZoneRestoreTarget> readZoneRestore(
            DataInputStream input) throws IOException {
        int present = input.readUnsignedByte();
        if (present > 1) throw new IOException("Invalid zone Restore target flag");
        return present == 0 ? Optional.empty() : Optional.of(new ZoneRestoreTarget(
                readUuid(input), readUuid(input), input.readLong()));
    }

    private static void writeHistoryKey(DataOutputStream output, HistoryKey key)
            throws IOException {
        if (key instanceof SectionKey section) {
            output.writeByte(1);
            output.writeInt(section.chunkX());
            output.writeInt(section.chunkZ());
            output.writeInt(section.sectionY());
        } else if (key instanceof EntityChunkKey entities) {
            output.writeByte(2);
            output.writeInt(entities.chunkX());
            output.writeInt(entities.chunkZ());
        } else {
            throw new IOException("Unsupported operation generation boundary key");
        }
    }

    private static HistoryKey readHistoryKey(DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 1 -> {
                int chunkX = input.readInt();
                int chunkZ = input.readInt();
                yield new SectionKey(chunkX, input.readInt(), chunkZ);
            }
            case 2 -> new EntityChunkKey(input.readInt(), input.readInt());
            default -> throw new IOException("Invalid operation generation boundary key kind");
        };
    }

    private static int keyKind(HistoryKey key) {
        return key instanceof SectionKey ? 1 : 2;
    }

    private static int keyChunkX(HistoryKey key) {
        return key instanceof SectionKey section
                ? section.chunkX() : ((EntityChunkKey) key).chunkX();
    }

    private static int keyChunkZ(HistoryKey key) {
        return key instanceof SectionKey section
                ? section.chunkZ() : ((EntityChunkKey) key).chunkZ();
    }

    private static int keySectionY(HistoryKey key) {
        return key instanceof SectionKey section ? section.sectionY() : 0;
    }

    private static void writeId(DataOutputStream output, CommitId id) throws IOException {
        output.write(HexFormat.of().parseHex(id.hex()));
    }

    private static CommitId readId(DataInputStream input) throws IOException {
        byte[] id = input.readNBytes(32);
        if (id.length != 32) {
            throw new IOException("Truncated commit ID");
        }
        return new CommitId(new ObjectId(HexFormat.of().formatHex(id)));
    }

    private static void writeUuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }
}
