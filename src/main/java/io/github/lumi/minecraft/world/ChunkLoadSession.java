package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.HistoryKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;

/** Retains all chunks needed by one operation and polls readiness within its deadline. */
public final class ChunkLoadSession implements AutoCloseable {
    private final ChunkLoadAccess access;
    private final LongSupplier nanoTime;
    private final Map<ChunkCoordinate, CompletableFuture<Void>> retained = new LinkedHashMap<>();
    private int next;
    private boolean closed;

    public ChunkLoadSession(ChunkLoadAccess access) {
        this(access, System::nanoTime);
    }

    ChunkLoadSession(ChunkLoadAccess access, LongSupplier nanoTime) {
        this.access = Objects.requireNonNull(access, "access");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public void retain(Iterable<? extends HistoryKey> keys) {
        if (closed) {
            throw new IllegalStateException("Chunk load session is closed");
        }
        for (HistoryKey key : keys) {
            ChunkCoordinate chunk = ChunkCoordinate.from(key);
            retained.computeIfAbsent(chunk, access::retain);
        }
    }

    public boolean loadUntil(long deadlineNanos) throws IOException {
        var chunks = new ArrayList<>(retained.entrySet());
        while (next < chunks.size() && nanoTime.getAsLong() < deadlineNanos) {
            var entry = chunks.get(next);
            if (!entry.getValue().isDone()) {
                return false;
            }
            try {
                entry.getValue().join();
            } catch (CompletionException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                throw new IOException("Cannot load Lumi chunk " + entry.getKey(), cause);
            }
            if (!access.isReady(entry.getKey())) {
                return false;
            }
            next++;
        }
        return next == chunks.size();
    }

    public int completedChunks() {
        return next;
    }

    public int totalChunks() {
        return retained.size();
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
