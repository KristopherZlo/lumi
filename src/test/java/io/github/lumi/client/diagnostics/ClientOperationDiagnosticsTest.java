package io.github.lumi.client.diagnostics;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.minecraft.operation.OperationProgress;
import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.network.RestoreStatisticsPayload;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.chat.ClickEvent;
import org.junit.jupiter.api.Test;

class ClientOperationDiagnosticsTest {
    @Test
    void reportsObservedAndExactRestorePhasesWithCopyAction() {
        long[] now = {0};
        var diagnostics = new ClientOperationDiagnostics(() -> now[0]);
        UUID request = UUID.randomUUID();

        diagnostics.accept(accepted(request), true);
        now[0] = millis(10);
        diagnostics.accept(progress(request, "Restore: diff"), true);
        now[0] = millis(30);
        diagnostics.accept(progress(request, "Restore: preflight"), true);
        now[0] = millis(50);
        var report = diagnostics.accept(terminal(request), true).orElseThrow();

        String text = report.getString();
        assertTrue(text.contains("total=50 ms"));
        assertTrue(text.contains("phase preparing=10 ms"));
        assertTrue(text.contains("phase Restore: diff=20 ms"));
        assertTrue(text.contains("phase Restore: preflight=20 ms"));
        assertTrue(text.contains("restore accuracy=exact (verified)"));
        assertTrue(text.contains("restore-phase lighting=7 ms"));
        assertTrue(text.contains("restore-phase storage-force=8 ms"));
        assertTrue(text.contains("changed-blocks=4096"));
        var copy = assertInstanceOf(
                ClickEvent.CopyToClipboard.class,
                report.getSiblings().getLast().getStyle().getClickEvent());
        assertTrue(copy.value().contains("request=" + request));
    }

    @Test
    void disabledModeClearsTracesButStillAllowsLaterErrors() {
        long[] now = {0};
        var diagnostics = new ClientOperationDiagnostics(() -> now[0]);
        UUID request = UUID.randomUUID();

        diagnostics.accept(accepted(request), true);
        assertTrue(diagnostics.accept(progress(request, "Restore: diff"), false)
                .isEmpty());
        var report = diagnostics.accept(new OperationEventPayload(
                request, "minecraft:overworld",
                OperationEventPayload.State.FAILED, "disk failed",
                id(), 3), true).orElseThrow().getString();

        assertTrue(report.contains("error=disk failed"));
        assertFalse(report.contains("phase Restore: diff"));
    }

    private static OperationEventPayload accepted(UUID request) {
        return new OperationEventPayload(
                request, "minecraft:overworld",
                OperationEventPayload.State.ACCEPTED, "Operation accepted",
                id(), 3, Optional.of(UUID.randomUUID()), 0);
    }

    private static OperationEventPayload progress(UUID request, String phase) {
        return new OperationEventPayload(
                request, "minecraft:overworld",
                OperationEventPayload.State.PROGRESS, phase, id(), 3,
                Optional.of(UUID.randomUUID()), 0,
                Optional.of(OperationProgress.indeterminate(phase)));
    }

    private static OperationEventPayload terminal(UUID request) {
        return new OperationEventPayload(
                request, "minecraft:overworld",
                OperationEventPayload.State.SUCCEEDED, "Restored", id(), 4,
                Optional.empty(), -1, Optional.empty(), Optional.empty(),
                Optional.of(new RestoreStatisticsPayload(
                        8, 2, 4096, 11,
                        millis(1), millis(7), millis(2), millis(3),
                        millis(4), millis(5), millis(6), millis(8), millis(9))));
    }

    private static long millis(long value) {
        return TimeUnit.MILLISECONDS.toNanos(value);
    }

    private static CommitId id() {
        return new CommitId(new ObjectId("a".repeat(64)));
    }
}
