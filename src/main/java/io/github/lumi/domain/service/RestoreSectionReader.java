package io.github.lumi.domain.service;

import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Reuses immutable section payloads while one lazy Restore plan is alive. */
final class RestoreSectionReader implements Closeable {
    static final int MAX_CACHED_SECTIONS = 32;

    private final WorldObjectRepository.ReadSession session;
    private final LinkedHashMap<ObjectId, SectionBlob> cache =
            new LinkedHashMap<>(MAX_CACHED_SECTIONS, 0.75F, true);

    RestoreSectionReader(WorldObjectRepository objects) {
        session = Objects.requireNonNull(objects, "objects").beginReadSession();
    }

    synchronized SectionBlob read(ObjectId id) throws IOException {
        Objects.requireNonNull(id, "id");
        SectionBlob cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        SectionBlob decoded = session.readSection(id);
        cache.put(id, decoded);
        if (cache.size() > MAX_CACHED_SECTIONS) {
            var eldest = cache.entrySet().iterator();
            eldest.next();
            eldest.remove();
        }
        return decoded;
    }

    synchronized int cachedSectionCount() {
        return cache.size();
    }

    @Override
    public synchronized void close() throws IOException {
        session.close();
    }
}
