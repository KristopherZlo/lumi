package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.WorldActionRecord;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.storage.RepositoryMaintenance;
import io.github.lumi.storage.object.WorldActionIndexCodec;
import io.github.lumi.storage.object.WorldActionPayloadCodec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Immutable action files become reachable only through an atomically replaced index. */
public final class WorldActionHistoryRepository {
    private static final int MAX_INDEX_BYTES = 32 * 1024 * 1024;
    private static final long MAX_PAYLOAD_FILE_BYTES = 128L * 1024 * 1024;
    private final Path root;
    private final RepositoryMaintenance maintenance;
    private final WorldActionIndexCodec indexes = new WorldActionIndexCodec();
    private final WorldActionPayloadCodec payloads = new WorldActionPayloadCodec();

    public WorldActionHistoryRepository(Path dimensionRepository) {
        root = dimensionRepository.resolve("actions");
        maintenance = RepositoryMaintenance.forRepository(dimensionRepository);
    }

    public RepositoryMaintenance.Publication beginPublication() {
        return maintenance.beginPublication();
    }

    public Optional<WorldActionIndexCodec.State> readIndex() throws IOException {
        Path file = root.resolve("index.bin.gz");
        if (!Files.exists(file)) return Optional.empty();
        try (var input = compressedInput(file, MAX_INDEX_BYTES)) {
            byte[] content = input.readNBytes(MAX_INDEX_BYTES + 1);
            if (content.length > MAX_INDEX_BYTES) throw new IOException("Action index exceeds its byte limit");
            return Optional.of(indexes.decode(new DataInputStream(new ByteArrayInputStream(content))));
        }
    }

    public void writeIndex(WorldActionIndexCodec.State state) throws IOException {
        try (var publication = beginPublication()) {
            for (UUID blob : state.payloads().values()) {
                if (!Files.isRegularFile(payloadPath(blob))) throw new IOException("Missing action payload: " + blob);
            }
            writeCompressed(root.resolve("index.bin.gz"), output -> indexes.encode(state, output));
        }
    }

    public UUID writePayload(WorldActionRecord record, WorldDataVersion version) throws IOException {
        UUID blob = UUID.randomUUID();
        writeCompressed(payloadPath(blob), output -> payloads.encode(record, version, output));
        return blob;
    }

    public WorldActionRecord readPayload(WorldActionRecord.Metadata metadata, UUID blob,
            WorldDataVersion version, long allocationBudget) throws IOException {
        try (var input = compressedInput(payloadPath(blob), MAX_PAYLOAD_FILE_BYTES)) {
            return payloads.decode(metadata, version, allocationBudget, input);
        }
    }

    /** Both checkpoint endpoints are roots, even when no branch currently refers to either. */
    public Set<CommitId> retainedCommits() throws IOException {
        var retained = new HashSet<CommitId>();
        var state = readIndex();
        if (state.isPresent()) state.orElseThrow().index().actions().forEach(action -> action.checkpoint().ifPresent(checkpoint -> {
            retained.add(checkpoint.before());
            retained.add(checkpoint.after());
        }));
        return Set.copyOf(retained);
    }

    /** Called by the single history writer after a successful index publication. */
    public void removeUnreferencedPayloads() throws IOException {
        var state = readIndex();
        if (state.isEmpty()) return;
        Set<String> retained = new HashSet<>();
        state.orElseThrow().payloads().values().forEach(id -> retained.add(id + ".bin.gz"));
        Path directory = root.resolve("payloads");
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.newDirectoryStream(directory, "*.bin.gz")) {
            for (Path file : files) {
                if (!retained.contains(file.getFileName().toString())) Files.deleteIfExists(file);
            }
        }
    }

    private Path payloadPath(UUID blob) { return root.resolve("payloads").resolve(blob + ".bin.gz"); }

    private DataInputStream compressedInput(Path file, long maximumBytes) throws IOException {
        if (Files.size(file) > maximumBytes) throw new IOException("Compressed action file exceeds its byte limit: " + file);
        var input = Files.newInputStream(file);
        try {
            return new DataInputStream(new GZIPInputStream(input, 16 * 1024));
        } catch (IOException failure) {
            input.close();
            throw failure;
        }
    }

    private void writeCompressed(Path target, Encoder encoder) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".action-", ".tmp");
        try {
            try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary), 16 * 1024))) {
                encoder.write(output);
            }
            if (Files.size(temporary) > MAX_PAYLOAD_FILE_BYTES) throw new IOException("Action file exceeds its byte limit");
            AtomicFileWriter.replacePrepared(target, temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @FunctionalInterface
    private interface Encoder { void write(DataOutputStream output) throws IOException; }
}
