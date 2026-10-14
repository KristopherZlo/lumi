package io.github.lumi.domain.service;

import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Reuses one object-store session while one lazy Restore direction is alive. */
final class RestorePlanReader implements Closeable {
    static final int MAX_CACHED_SECTIONS = 32;

    private final RestorePlanMap.Reader<ObjectId, SectionBlob> sectionReader;
    private final Closeable resource;
    private final LinkedHashMap<ObjectId, SectionBlob> cache =
            new LinkedHashMap<>(MAX_CACHED_SECTIONS, 0.75F, true);
    private boolean reading;
    private boolean closed;
    private boolean resourceClosed;

    RestorePlanReader(WorldObjectRepository objects) {
        this(Objects.requireNonNull(objects, "objects").beginReadSession());
    }

    private RestorePlanReader(WorldObjectRepository.ReadSession session) {
        this(session::readSection, session);
    }

    RestorePlanReader(
            RestorePlanMap.Reader<ObjectId, SectionBlob> sectionReader,
            Closeable resource) {
        this.sectionReader = Objects.requireNonNull(sectionReader, "sectionReader");
        this.resource = Objects.requireNonNull(resource, "resource");
    }

    SectionBlob readSection(ObjectId id) throws IOException {
        Objects.requireNonNull(id, "id");
        synchronized (this) {
            requireOpen();
            SectionBlob cached = cache.get(id);
            if (cached != null) {
                return cached;
            }
        }
        SectionBlob decoded = read(id, sectionReader);
        synchronized (this) {
            if (!closed) {
                cache.put(id, decoded);
                if (cache.size() > MAX_CACHED_SECTIONS) {
                    var eldest = cache.entrySet().iterator();
                    eldest.next();
                    eldest.remove();
                }
            }
        }
        return decoded;
    }

    private <T> T read(
            ObjectId id, RestorePlanMap.Reader<ObjectId, T> reader) throws IOException {
        synchronized (this) {
            requireOpen();
            beginRead();
        }
        Throwable failure = null;
        try {
            return reader.read(id);
        } catch (IOException | RuntimeException | Error failed) {
            failure = failed;
            throw failed;
        } finally {
            finishRead(failure);
        }
    }

    synchronized int cachedSectionCount() {
        return cache.size();
    }

    @Override
    public void close() throws IOException {
        boolean closeNow;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            cache.clear();
            closeNow = !reading;
            if (closeNow) {
                resourceClosed = true;
            }
        }
        if (closeNow) {
            resource.close();
        }
    }

    private void beginRead() throws IOException {
        if (reading) {
            throw new IOException("Concurrent Restore plan reads are not supported");
        }
        reading = true;
    }

    private void finishRead(Throwable failure) throws IOException {
        boolean closeNow;
        synchronized (this) {
            reading = false;
            closeNow = closed && !resourceClosed;
            if (closeNow) {
                resourceClosed = true;
            }
        }
        if (!closeNow) {
            return;
        }
        try {
            resource.close();
        } catch (IOException closeFailure) {
            if (failure != null) {
                failure.addSuppressed(closeFailure);
            } else {
                throw closeFailure;
            }
        }
    }

    private void requireOpen() throws IOException {
        if (closed) {
            throw new IOException("Restore plan reader is closed");
        }
    }
}
