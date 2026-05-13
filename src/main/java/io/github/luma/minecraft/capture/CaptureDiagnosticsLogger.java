package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.debug.CaptureSkipLogThrottler;
import net.minecraft.core.BlockPos;

/**
 * Owns high-volume capture diagnostics and their throttling policy.
 */
final class CaptureDiagnosticsLogger {

    private static final int STARTUP_CAPTURE_TRACE_LIMIT = 32;
    private static final int CAPTURE_SUMMARY_ENTRY_LIMIT = 4;

    private final CaptureSkipLogThrottler skipLogThrottler = new CaptureSkipLogThrottler();

    void logSkippedCapture(
            TrackedProject trackedProject,
            WorldMutationSource source,
            BlockPos pos,
            String reason,
            String detail
    ) {
        if (trackedProject == null || !LumaDebugLog.enabled(trackedProject.project())) {
            return;
        }
        BuildProject project = trackedProject.project();
        CaptureSkipLogThrottler.Decision decision = this.skipLogThrottler.record(project, source, reason, pos);
        if (!decision.shouldLog()) {
            return;
        }
        if (decision.logSample()) {
            LumaDebugLog.log(
                    project,
                    "capture",
                    "Skipped {} mutation at {} for project {} because {}",
                    source,
                    pos,
                    project.name(),
                    detail
            );
            return;
        }
        LumaDebugLog.log(
                project,
                "capture",
                "Suppressed {} skipped {} mutation logs for project {} reason={} latest={}",
                decision.suppressedSinceLastLog(),
                source,
                project.name(),
                reason,
                decision.latestPos()
        );
    }

    void logReconciliation(
            TrackedProject trackedProject,
            SessionStabilizationService.ReconciliationResult result
    ) {
        String message = "Reconciled {} dirty chunks for project {}: delta={} composed={} buffer {} -> {}";
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                message,
                result.chunkCount(),
                trackedProject.project().name(),
                result.deltaChangeCount(),
                result.composedChangeCount(),
                result.bufferBefore(),
                result.bufferAfter()
        );
        if (result.bufferChanged()) {
            LumaMod.LOGGER.info(
                    message,
                    result.chunkCount(),
                    trackedProject.project().name(),
                    result.deltaChangeCount(),
                    result.composedChangeCount(),
                    result.bufferBefore(),
                    result.bufferAfter()
            );
        }
    }

    void logAcceptedCaptureTrace(
            BuildProject project,
            TrackedChangeBuffer buffer,
            CaptureSessionDiagnostics diagnostics,
            int pendingBefore,
            int pendingAfter
    ) {
        int accepted = diagnostics.acceptedMutations();
        if (accepted <= STARTUP_CAPTURE_TRACE_LIMIT) {
            LumaDebugLog.log(
                    project,
                    "capture",
                    "Capture trace {}/{} for project {}: source={} sessionSource={} pos={} chunk={}:{} {} -> {} oldBe={} newBe={} pending={} delta={}",
                    accepted,
                    STARTUP_CAPTURE_TRACE_LIMIT,
                    project.name(),
                    diagnostics.lastSource(),
                    buffer.mutationSource(),
                    this.formatPos(diagnostics.lastPos()),
                    diagnostics.lastChunk().x(),
                    diagnostics.lastChunk().z(),
                    diagnostics.lastOldBlockId(),
                    diagnostics.lastNewBlockId(),
                    diagnostics.lastOldBlockEntity(),
                    diagnostics.lastNewBlockEntity(),
                    pendingAfter,
                    pendingAfter - pendingBefore
            );
            if (accepted == STARTUP_CAPTURE_TRACE_LIMIT) {
                LumaDebugLog.log(
                        project,
                        "capture",
                        "Capture trace limit reached for project {}. Further accepted mutations in this session will be summarized only at progress checkpoints.",
                        project.name()
                );
            }
        }
    }

    void logBufferProgress(BuildProject project, TrackedChangeBuffer buffer, CaptureSessionDiagnostics diagnostics) {
        int size = buffer.size();
        if (this.shouldLogBufferProgress(size)) {
            LumaMod.LOGGER.info(
                    "Captured {} pending changes for project {} (accepted={} sources=[{}] transitions=[{}] last={} source={} chunk={}:{})",
                    size,
                    project.name(),
                    diagnostics.acceptedMutations(),
                    diagnostics.describeTopSources(CAPTURE_SUMMARY_ENTRY_LIMIT),
                    diagnostics.describeTopTransitions(CAPTURE_SUMMARY_ENTRY_LIMIT),
                    this.formatPos(diagnostics.lastPos()),
                    diagnostics.lastSource(),
                    diagnostics.lastChunk().x(),
                    diagnostics.lastChunk().z()
            );
        }
    }

    boolean shouldLogBufferProgress(int size) {
        return size == 1 || size == 64 || size == 256 || (size % 1024) == 0;
    }

    String formatPos(BlockPos pos) {
        if (pos == null) {
            return "unknown";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
