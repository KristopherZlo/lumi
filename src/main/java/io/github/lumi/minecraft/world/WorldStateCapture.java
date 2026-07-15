package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.CapturedWorldState;

/** Server-thread port that copies decoded immutable state in bounded batches. */
public interface WorldStateCapture {
    CaptureSession begin(WorkingIndexSnapshot dirty);

    interface CaptureSession {
        boolean captureUntil(long deadlineNanos);

        CapturedWorldState finish();
    }
}
