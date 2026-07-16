package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.WorkingIndexSnapshot;
import java.io.IOException;
import java.util.Objects;

/** Loads the filtered durable boundary before server-thread world capture begins. */
public final class ChunkLoadingSavePreparation implements SavePreparation {
    private final SavePreparation delegate;
    private final ChunkLoadSession chunks;

    public ChunkLoadingSavePreparation(
            SavePreparation delegate, ChunkLoadSession chunks) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
    }

    @Override
    public Session begin() {
        return new LoadingSession(delegate.begin());
    }

    private final class LoadingSession implements Session {
        private final Session delegateSession;
        private WorkingIndexSnapshot boundary;

        private LoadingSession(Session delegateSession) {
            this.delegateSession = Objects.requireNonNull(delegateSession, "delegate session");
        }

        @Override
        public boolean prepareUntil(long deadlineNanos) throws IOException {
            if (boundary == null) {
                if (!delegateSession.prepareUntil(deadlineNanos)) {
                    return false;
                }
                boundary = delegateSession.finish();
                chunks.retain(boundary.generations().keySet());
            }
            return chunks.loadUntil(deadlineNanos);
        }

        @Override
        public WorkingIndexSnapshot finish() {
            if (boundary == null) {
                throw new IllegalStateException("Chunk loading preparation is not complete");
            }
            return boundary;
        }

        @Override
        public void close() throws IOException {
            try {
                delegateSession.close();
            } finally {
                chunks.close();
            }
        }
    }
}
