package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.HistoryKey;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;

/** Retains all chunks needed by one operation and polls readiness within its deadline. */
public final class ChunkLoadSession implements AutoCloseable {
    private final ChunkLoadAccess access;
    private final LongSupplier nanoTime;
    private final Map<ChunkCoordinate, CompletableFuture<Void>> retained = new LinkedHashMap<>();
    private final Set<ChunkCoordinate> ready = new HashSet<>();
    private final ArrayDeque<Iterator<? extends HistoryKey>> pendingRetentions =
            new ArrayDeque<>();
    private Iterator<Map.Entry<ChunkCoordinate, CompletableFuture<Void>>> loading;
    private Map.Entry<ChunkCoordinate, CompletableFuture<Void>> current;
    private int completed;
    private boolean loadingStarted;
    private boolean windowed;
    private boolean closed;

    public ChunkLoadSession(ChunkLoadAccess access) {
        this(access, System::nanoTime);
    }

    ChunkLoadSession(ChunkLoadAccess access, LongSupplier nanoTime) {
        this.access = Objects.requireNonNull(access, "access");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public void retain(Iterable<? extends HistoryKey> keys) {
        requireRetainable();
        pendingRetentions.add(Objects.requireNonNull(keys, "keys").iterator());
    }

    public boolean loadUntil(long deadlineNanos) throws IOException {
        if (windowed) {
            throw new IllegalStateException("Windowed chunk loading is already active");
        }
        loadingStarted = true;
        while (nanoTime.getAsLong() < deadlineNanos) {
            if (retainNext()) {
                continue;
            }
            if (loading == null) {
                access.startLoading();
                loading = retained.entrySet().iterator();
            }
            if (current == null) {
                if (!loading.hasNext()) {
                    return true;
                }
                current = loading.next();
            }
            if (!current.getValue().isDone()) {
                return false;
            }
            try {
                current.getValue().join();
            } catch (CompletionException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                throw new IOException("Cannot load Lumi chunk " + current.getKey(), cause);
            }
            if (!access.isReady(current.getKey())) {
                return false;
            }
            current = null;
            completed++;
        }
        return false;
    }

    private boolean retainNext() {
        if (pendingRetentions.isEmpty()) {
            return false;
        }
        Iterator<? extends HistoryKey> keys = pendingRetentions.getFirst();
        if (keys.hasNext()) {
            retainKey(keys.next());
        } else {
            pendingRetentions.removeFirst();
        }
        return true;
    }

    private void retainKey(HistoryKey key) {
        ChunkCoordinate chunk = ChunkCoordinate.from(key);
        retained.computeIfAbsent(chunk, access::retain);
    }

    public boolean loadOneUntil(
            ChunkCoordinate chunk, long deadlineNanos) throws IOException {
        Objects.requireNonNull(chunk, "chunk");
        if (closed) {
            throw new IllegalStateException("Chunk load session is closed");
        }
        if (!pendingRetentions.isEmpty() || (loadingStarted && !windowed)) {
            throw new IllegalStateException("Bulk chunk loading is already active");
        }
        windowed = true;
        loadingStarted = true;
        if (ready.contains(chunk)) {
            return true;
        }
        CompletableFuture<Void> future = retained.get(chunk);
        if (future == null) {
            future = access.retain(chunk);
            retained.put(chunk, future);
            access.startLoading();
        }
        if (nanoTime.getAsLong() >= deadlineNanos || !future.isDone()) {
            return false;
        }
        try {
            future.join();
        } catch (CompletionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            throw new IOException("Cannot load Lumi chunk " + chunk, cause);
        }
        if (!access.isReady(chunk)) {
            return false;
        }
        ready.add(chunk);
        return true;
    }

    public void release(ChunkCoordinate chunk) {
        CompletableFuture<Void> removed = retained.remove(
                Objects.requireNonNull(chunk, "chunk"));
        if (removed != null) {
            ready.remove(chunk);
            access.release(chunk);
            completed++;
        }
    }

    public int completedChunks() {
        return completed;
    }

    public int totalChunks() {
        return retained.size();
    }

    private void requireRetainable() {
        if (closed) {
            throw new IllegalStateException("Chunk load session is closed");
        }
        if (loadingStarted) {
            throw new IllegalStateException("Cannot add chunks after loading started");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        retained.keySet().forEach(access::release);
    }
}
