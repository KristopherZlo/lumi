package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.CapturedWorldState;
import java.io.IOException;

/** Server-thread port that copies decoded immutable state in bounded batches. */
public interface WorldStateCapture {
    CaptureSession begin(WorkingIndexSnapshot dirty);

    static WorldStateCapture captured(CapturedWorldState state) {
        return dirty -> {
            if (!state.generations().equals(dirty)) throw new IllegalArgumentException("Capture boundary changed");
            return new CaptureSession() {
                @Override public boolean captureUntil(long deadlineNanos) { return true; }
                @Override public CapturedWorldState finish() { return state; }
                @Override public long completedKeys() { return state.generations().generations().size(); }
                @Override public long totalKeys() { return completedKeys(); }
            };
        };
    }

    interface CaptureSession extends AutoCloseable {
        boolean captureUntil(long deadlineNanos) throws IOException;

        CapturedWorldState finish();

        default long completedKeys() {
            return 0;
        }

        default long totalKeys() {
            return 0;
        }

        @Override
        default void close() throws IOException { }
    }
}
