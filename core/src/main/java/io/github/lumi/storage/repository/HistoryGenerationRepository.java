package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.HistoryGeneration;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.domain.service.WorldDataCompatibility;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Publishes verified generations; callers retain exclusive dimension mutation ownership. */
public final class HistoryGenerationRepository {
    private static final int MAGIC = 0x4C484731; // LHG1
    private static final int GENERATION_BYTES = 2 * Long.BYTES + Integer.BYTES;
    private final Path root;
    private final Path pointer;
    private final AtomicFileWriter.FailurePoint beforePublication;

    public HistoryGenerationRepository(Path dimensionRepository) {
        this(dimensionRepository, () -> { });
    }

    HistoryGenerationRepository(Path root, AtomicFileWriter.FailurePoint beforePublication) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        pointer = this.root.resolve("generations").resolve("active.bin");
        this.beforePublication = Objects.requireNonNull(beforePublication, "beforePublication");
    }

    public Path directory(HistoryGeneration generation) {
        return generation.legacy() ? root
                : root.resolve("generations").resolve(generation.id().toString());
    }

    public synchronized HistoryGeneration active() throws IOException {
        if (!Files.exists(pointer)) return HistoryGeneration.LEGACY;
        HistoryGeneration generation = decode(pointer, 1)[0];
        if (generation.legacy()) throw new IOException("An active generation pointer cannot select legacy history");
        HistoryGeneration[] verified = decode(verifiedFile(generation), 2);
        if (!generation.equals(verified[1])) throw new IOException("Generation verification does not match pointer");
        WorldDataCompatibility.requireForward(verified[0].dataVersion(), generation.dataVersion());
        return generation;
    }

    public synchronized HistoryGeneration prepare(
            HistoryGeneration expected, WorldDataVersion target) throws IOException {
        requireActive(expected);
        WorldDataCompatibility.requireForward(expected.dataVersion(), target);
        HistoryGeneration prepared = new HistoryGeneration(UUID.randomUUID(), target);
        Files.createDirectories(directory(prepared).getParent());
        Files.createDirectory(directory(prepared));
        return prepared;
    }

    /** Called only after the conversion service has durably validated every root and sidecar. */
    public synchronized void markVerified(
            HistoryGeneration expected, HistoryGeneration prepared) throws IOException {
        requireActive(expected);
        requireCandidate(expected, prepared);
        Path file = verifiedFile(prepared);
        byte[] payload = encode(expected, prepared);
        if (!AtomicFileWriter.createOnce(file, payload)
                && !java.util.Arrays.equals(payload, RepositoryFileReader.read(file, payload.length))) {
            throw new IOException("Generation was verified for a different source");
        }
    }

    public synchronized void publish(
            HistoryGeneration expected, HistoryGeneration prepared) throws IOException {
        requireActive(expected);
        requireCandidate(expected, prepared);
        HistoryGeneration[] verified = decode(verifiedFile(prepared), 2);
        if (!expected.equals(verified[0]) || !prepared.equals(verified[1])) {
            throw new IOException("Generation was not verified against the active source");
        }
        AtomicFileWriter.replace(pointer, encode(prepared), beforePublication);
    }

    private void requireActive(HistoryGeneration expected) throws IOException {
        if (!active().equals(Objects.requireNonNull(expected, "expected"))) {
            throw new RefConflictException("Active history generation changed during conversion");
        }
    }

    private void requireCandidate(HistoryGeneration expected, HistoryGeneration prepared) throws IOException {
        if (prepared.legacy() || prepared.equals(expected) || !Files.isDirectory(directory(prepared))) {
            throw new IOException("Invalid prepared history generation");
        }
        WorldDataCompatibility.requireForward(expected.dataVersion(), prepared.dataVersion());
    }

    private Path verifiedFile(HistoryGeneration generation) {
        return directory(generation).resolve("verified.bin");
    }

    private static byte[] encode(HistoryGeneration... generations) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            for (HistoryGeneration generation : generations) {
                output.writeLong(generation.id().getMostSignificantBits());
                output.writeLong(generation.id().getLeastSignificantBits());
                output.writeInt(generation.dataVersion().value());
            }
        }
        return bytes.toByteArray();
    }

    private static HistoryGeneration[] decode(Path file, int count) throws IOException {
        byte[] bytes = RepositoryFileReader.read(file, Integer.BYTES + count * GENERATION_BYTES);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid history generation header");
            var generations = new HistoryGeneration[count];
            for (int index = 0; index < count; index++) {
                generations[index] = new HistoryGeneration(new UUID(input.readLong(), input.readLong()),
                        new WorldDataVersion(input.readInt()));
            }
            if (input.available() != 0) throw new IOException("Trailing history generation bytes");
            return generations;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid history generation metadata", invalid);
        }
    }
}
