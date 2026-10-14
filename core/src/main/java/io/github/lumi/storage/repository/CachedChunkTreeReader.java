package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Operation-local reuse of already verified immutable chunk metadata during Save. */
final class CachedChunkTreeReader {
    private static final int MAX_REFERENCES = 4096;
    private static final int MAX_NODES = 256;
    private final Reader reader;
    private final LinkedHashMap<ObjectId, ChunkTree> cached =
            new LinkedHashMap<>(16, 0.75F, true);
    private int references;

    CachedChunkTreeReader(Reader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    ChunkTree read(ObjectId id) throws IOException {
        ChunkTree chunk = cached.get(id);
        if (chunk != null) {
            return chunk;
        }
        chunk = reader.read(id);
        int weight = weight(chunk);
        if (weight <= MAX_REFERENCES) {
            while (!cached.isEmpty() && (references + weight > MAX_REFERENCES
                    || cached.size() >= MAX_NODES)) {
                var eldest = cached.entrySet().iterator();
                references -= weight(eldest.next().getValue());
                eldest.remove();
            }
            cached.put(id, chunk);
            references += weight;
        }
        return chunk;
    }

    private static int weight(ChunkTree chunk) {
        return 1 + chunk.sections().size() + (chunk.entities().isPresent() ? 1 : 0);
    }

    @FunctionalInterface
    interface Reader {
        ChunkTree read(ObjectId id) throws IOException;
    }
}
