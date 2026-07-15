package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class OperationJournalRepository {
    private static final int MAGIC = 0x4C4F4A32;
    private static final int MAX_NAME_BYTES = 1024;
    private final Path journalFile;

    public OperationJournalRepository(Path dimensionRepository) {
        journalFile = Objects.requireNonNull(dimensionRepository, "dimensionRepository")
                .resolve("operations").resolve("active.bin");
    }

    public synchronized OperationJournal create(OperationJournal journal) throws IOException {
        if (Files.exists(journalFile)) {
            throw new JournalConflictException("A dimension operation journal is already active");
        }
        AtomicFileWriter.replace(journalFile, encode(journal));
        return journal;
    }

    public synchronized OperationJournal advance(OperationJournal expected, OperationPhase next) throws IOException {
        OperationJournal current = read().orElseThrow(
                () -> new JournalConflictException("Active operation journal is missing"));
        if (!current.equals(expected)) {
            throw new JournalConflictException("Active operation journal changed");
        }
        OperationJournal advanced = current.withPhase(next);
        AtomicFileWriter.replace(journalFile, encode(advanced));
        return advanced;
    }

    public synchronized void clear(OperationJournal expected) throws IOException {
        if (!read().filter(expected::equals).isPresent()) {
            throw new JournalConflictException("Active operation journal changed");
        }
        Files.delete(journalFile);
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
        }
        return bytes.toByteArray();
    }

    private OperationJournal decode(byte[] payload) throws IOException {
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
            OperationTarget target = new OperationTarget(
                    new BranchName(new String(branch, StandardCharsets.UTF_8)),
                    readId(input), input.readLong(), readOptionalId(input), readOptionalId(input));
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in operation journal");
            }
            return new OperationJournal(operationId, kind, phase, target);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid operation journal", invalid);
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
