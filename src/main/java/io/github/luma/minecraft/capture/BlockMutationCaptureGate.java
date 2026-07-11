package io.github.luma.minecraft.capture;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.WorldMutationSource;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Owns the cheap gates that run before a block mutation pays payload-capture
 * cost.
 */
final class BlockMutationCaptureGate {

    private final CaptureEligibilityService eligibility;
    private final CaptureDiagnosticsLogger diagnosticsLogger;

    BlockMutationCaptureGate(
            CaptureEligibilityService eligibility,
            CaptureDiagnosticsLogger diagnosticsLogger
    ) {
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.diagnosticsLogger = Objects.requireNonNull(diagnosticsLogger, "diagnosticsLogger");
    }

    boolean canInspectPayload(
            TrackedProject trackedProject,
            WorldMutationSource source,
            BlockPos pos,
            boolean hasActiveSession,
            boolean activeSessionRegion
    ) {
        if (this.eligibility.canInspectBlockMutationPayload(
                trackedProject.project(),
                source,
                hasActiveSession,
                activeSessionRegion
        )) {
            return true;
        }
        this.diagnosticsLogger.logSkippedCapture(
                trackedProject,
                source,
                pos,
                "no-capture-path",
                "no explicit root source or active session region is available"
        );
        return false;
    }

    WorldMutationCapturePolicy.CaptureResult evaluate(
            WorldMutationSource source,
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            CompoundTag oldBlockEntity,
            CompoundTag newBlockEntity
    ) {
        return this.eligibility.evaluateBlockMutation(
                source,
                pos,
                oldState,
                newState,
                oldBlockEntity,
                newBlockEntity
        );
    }

    void logRejected(
            ServerLevel level,
            WorldMutationSource source,
            BlockPos pos,
            BlockState oldState,
            BlockState newState
    ) {
        LumaDebugLog.log(
                "capture",
                "Skipped {} mutation at {} in {} because it is unsupported, unchanged, or transient: {} -> {}",
                source,
                pos,
                level.dimension().identifier(),
                oldState,
                newState
        );
    }
}
