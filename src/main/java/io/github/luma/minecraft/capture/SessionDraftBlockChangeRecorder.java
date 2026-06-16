package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import java.time.Instant;

/**
 * Adds captured block mutations to a working draft using the session baseline
 * as the stable old side of the persisted delta.
 */
final class SessionDraftBlockChangeRecorder {

    private final SessionBaselineStateResolver baselineStateResolver;

    SessionDraftBlockChangeRecorder() {
        this(new SessionBaselineStateResolver());
    }

    SessionDraftBlockChangeRecorder(SessionBaselineStateResolver baselineStateResolver) {
        this.baselineStateResolver = baselineStateResolver;
    }

    Result record(
            CaptureSessionState session,
            TrackedChangeBuffer buffer,
            StoredBlockChange capturedChange,
            Instant now
    ) {
        int pendingBefore = buffer == null ? 0 : buffer.size();
        StoredBlockChange draftChange = this.baselineStateResolver.rebaseToSessionBaseline(session, capturedChange);
        if (buffer != null && draftChange != null && !draftChange.isNoOp()) {
            buffer.addChange(draftChange, now);
        }
        int pendingAfter = buffer == null ? pendingBefore : buffer.size();
        return new Result(pendingBefore, pendingAfter);
    }

    record Result(
            int pendingBefore,
            int pendingAfter
    ) {
    }
}
