package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class WorkingIndexRepository {
    private static final int MAGIC_V2 = 0x4C574932;
    private static final int MAGIC_V3 = 0x4C574933;
    private static final int MAX_KEYS = 1_000_000;
    private static final int MAX_FILE_BYTES = 2 * Integer.BYTES
            + MAX_KEYS * (1 + 3 * Integer.BYTES + 2 * Long.BYTES);
    private static final Comparator<HistoryKey> KEY_ORDER = Comparator
            .comparingInt(WorkingIndexRepository::kind)
            .thenComparingInt(WorkingIndexRepository::chunkX)
            .thenComparingInt(WorkingIndexRepository::chunkZ)
            .thenComparingInt(WorkingIndexRepository::sectionY);
    private final Path indexFile;

    public WorkingIndexRepository(Path dimensionRepository) {
        indexFile = Objects.requireNonNull(dimensionRepository, "dimensionRepository")
                .resolve("working").resolve("index.bin");
    }

    public synchronized void write(WorkingIndexSnapshot snapshot) throws IOException {
        write(new State(snapshot, WorkingIndexSnapshot.empty()));
    }

    public synchronized void write(State state) throws IOException {
        Objects.requireNonNull(state, "state");
        WorkingIndexSnapshot snapshot = state.working();
        if (snapshot.generations().size() > MAX_KEYS) {
            throw new IOException("Working index exceeds " + MAX_KEYS + " keys");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC_V3);
            output.writeInt(snapshot.generations().size());
            var keys = new ArrayList<>(snapshot.generations().keySet());
            keys.sort(KEY_ORDER);
            for (HistoryKey key : keys) {
                writeKey(output, key);
                output.writeLong(snapshot.generations().get(key));
                output.writeLong(state.builder().generations().getOrDefault(key, 0L));
            }
        }
        AtomicFileWriter.replace(indexFile, bytes.toByteArray());
    }

    public synchronized WorkingIndexSnapshot read() throws IOException {
        return readState().working();
    }

    /** Clears only generations proven to have been included in a published operation. */
    public synchronized void clearCaptured(WorkingIndexSnapshot captured) throws IOException {
        Objects.requireNonNull(captured, "captured");
        State current = readState();
        Map<HistoryKey, Long> working = new LinkedHashMap<>(
                current.working().generations());
        captured.generations().forEach(working::remove);
        Map<HistoryKey, Long> builder = new LinkedHashMap<>(
                current.builder().generations());
        builder.entrySet().removeIf(entry -> {
            Long generation = captured.generations().get(entry.getKey());
            return generation != null && generation >= entry.getValue();
        });
        State updated = new State(
                new WorkingIndexSnapshot(working), new WorkingIndexSnapshot(builder));
        if (!updated.equals(current)) {
            write(updated);
        }
    }

    public synchronized State readState() throws IOException {
        if (!Files.exists(indexFile)) {
            return new State(WorkingIndexSnapshot.empty(), WorkingIndexSnapshot.empty());
        }
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(RepositoryFileReader.read(
                        indexFile, MAX_FILE_BYTES)))) {
            int magic = input.readInt();
            if (magic != MAGIC_V2 && magic != MAGIC_V3) {
                throw new IOException("Not a Lumi V2 working index");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_KEYS) {
                throw new IOException("Invalid working index size");
            }
            Map<HistoryKey, Long> generations = new LinkedHashMap<>();
            Map<HistoryKey, Long> builderGenerations = new LinkedHashMap<>();
            HistoryKey previous = null;
            for (int index = 0; index < count; index++) {
                HistoryKey key = readKey(input);
                long generation = input.readLong();
                if (generation < 1 || (previous != null && KEY_ORDER.compare(previous, key) >= 0)) {
                    throw new IOException("Working index is not canonical");
                }
                previous = key;
                generations.put(key, generation);
                if (magic == MAGIC_V3) {
                    long builderGeneration = input.readLong();
                    if (builderGeneration < 0 || builderGeneration > generation) {
                        throw new IOException("Invalid builder generation");
                    }
                    if (builderGeneration > 0) {
                        builderGenerations.put(key, builderGeneration);
                    }
                }
            }
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in working index");
            }
            WorkingIndexSnapshot working = new WorkingIndexSnapshot(generations);
            return new State(working, magic == MAGIC_V2
                    ? working : new WorkingIndexSnapshot(builderGenerations));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid working index", invalid);
        }
    }

    public record State(
            WorkingIndexSnapshot working,
            WorkingIndexSnapshot builder) {
        public State {
            Objects.requireNonNull(working, "working");
            Objects.requireNonNull(builder, "builder");
            builder.generations().forEach((key, generation) -> {
                Long dirtyGeneration = working.generations().get(key);
                if (dirtyGeneration == null || generation > dirtyGeneration) {
                    throw new IllegalArgumentException(
                            "Builder generations must be a subset of the working index");
                }
            });
        }
    }

    private static void writeKey(DataOutputStream output, HistoryKey key) throws IOException {
        if (key instanceof SectionKey section) {
            output.writeByte(1);
            output.writeInt(section.chunkX());
            output.writeInt(section.chunkZ());
            output.writeInt(section.sectionY());
        } else {
            EntityChunkKey entities = (EntityChunkKey) key;
            output.writeByte(2);
            output.writeInt(entities.chunkX());
            output.writeInt(entities.chunkZ());
        }
    }

    private static HistoryKey readKey(DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 1 -> {
                int x = input.readInt();
                int z = input.readInt();
                yield new SectionKey(x, input.readInt(), z);
            }
            case 2 -> new EntityChunkKey(input.readInt(), input.readInt());
            default -> throw new IOException("Invalid working index key kind");
        };
    }

    private static int kind(HistoryKey key) {
        return key instanceof SectionKey ? 1 : 2;
    }

    private static int chunkX(HistoryKey key) {
        return key instanceof SectionKey section ? section.chunkX() : ((EntityChunkKey) key).chunkX();
    }

    private static int chunkZ(HistoryKey key) {
        return key instanceof SectionKey section ? section.chunkZ() : ((EntityChunkKey) key).chunkZ();
    }

    private static int sectionY(HistoryKey key) {
        return key instanceof SectionKey section ? section.sectionY() : 0;
    }
}
