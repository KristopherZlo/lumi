package io.github.luma.minecraft.world;

import io.github.luma.domain.model.OperationHandle;
import net.minecraft.server.level.ServerLevel;

/**
 * Applies exact replay targets without allowing one corrupt target to escape or
 * retry forever.
 */
final class SafeExactReplayStateApplier {

    private final ExactReplayPlacementApplier delegate;
    private final WorldApplyExceptionTracker failureTracker;

    SafeExactReplayStateApplier() {
        this(new MinecraftExactReplayPlacementApplier(), new WorldApplyExceptionTracker());
    }

    SafeExactReplayStateApplier(
            ExactReplayPlacementApplier delegate,
            WorldApplyExceptionTracker failureTracker
    ) {
        this.delegate = delegate == null ? new MinecraftExactReplayPlacementApplier() : delegate;
        this.failureTracker = failureTracker == null ? new WorldApplyExceptionTracker() : failureTracker;
    }

    ApplyResult apply(
            ServerLevel level,
            PreparedBlockPlacement placement,
            OperationHandle handle,
            String phase
    ) {
        if (placement == null || placement.pos() == null || placement.state() == null) {
            return ApplyResult.ofSkipped();
        }
        if (this.failureTracker.isQuarantined(phase, placement.pos())) {
            return ApplyResult.ofQuarantined();
        }
        try {
            boolean applied = this.delegate.apply(level, placement, handle, phase);
            this.failureTracker.clear(phase, placement.pos());
            return applied ? ApplyResult.ofApplied() : ApplyResult.ofSkipped();
        } catch (Exception exception) {
            WorldApplyExceptionTracker.FailureDecision decision =
                    this.failureTracker.recordFailure(phase, placement.pos(), exception);
            WorldApplyExceptionLogger.record(
                    decision,
                    handle == null ? "" : "operationId=" + handle.id() + ", label=" + handle.label()
            );
            return decision.quarantined() ? ApplyResult.ofQuarantined() : ApplyResult.ofFailed();
        }
    }

    record ApplyResult(boolean applied, boolean failed, boolean quarantined) {

        static ApplyResult ofApplied() {
            return new ApplyResult(true, false, false);
        }

        static ApplyResult ofFailed() {
            return new ApplyResult(false, true, false);
        }

        static ApplyResult ofQuarantined() {
            return new ApplyResult(false, false, true);
        }

        static ApplyResult ofSkipped() {
            return new ApplyResult(false, false, false);
        }
    }
}
