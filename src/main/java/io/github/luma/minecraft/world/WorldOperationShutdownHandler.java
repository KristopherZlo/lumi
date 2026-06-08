package io.github.luma.minecraft.world;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDiagnosticsLog;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.telemetry.TelemetryService;
import java.time.Duration;
import net.minecraft.server.MinecraftServer;

/**
 * Handles active world operation drain/failure during server shutdown.
 */
final class WorldOperationShutdownHandler {

    private static final Duration SERVER_STOP_LIGHT_REFRESH_GRACE = Duration.ofSeconds(2);
    private static final long SERVER_STOP_LIGHT_REFRESH_PAUSE_MILLIS = 10L;
    private static final double MAX_ADAPTIVE_SCALE = 1.25D;

    private final WorldOperationLifecycle lifecycle;
    private final WorldApplyBudgetPlanner budgetPlanner;
    private final OperationCompleter operationCompleter;

    WorldOperationShutdownHandler(
            WorldOperationLifecycle lifecycle,
            WorldApplyBudgetPlanner budgetPlanner,
            OperationCompleter operationCompleter
    ) {
        this.lifecycle = lifecycle;
        this.budgetPlanner = budgetPlanner;
        this.operationCompleter = operationCompleter;
    }

    void finishServerOperationBeforeShutdown(String serverKey, MinecraftServer server) {
        WorldOperationManager.ActiveOperation operation = this.lifecycle.active(serverKey);
        if (operation == null) {
            return;
        }

        if (operation.drainBeforeShutdown()) {
            this.tryCompleteBeforeShutdown(serverKey, server, operation);
        }

        WorldOperationManager.ActiveOperation active = this.lifecycle.removeActive(serverKey);
        if (active == null) {
            return;
        }
        if (!active.snapshot().terminal()) {
            active.fail(new IllegalStateException("Server stopped before world operation completed"));
            TelemetryService.getInstance().recordOperationFailed(
                    active.handle(),
                    active.snapshot(),
                    new IllegalStateException("Server stopped before world operation completed")
            );
        }
        this.lifecycle.remember(serverKey, active)
                .ifPresent(metrics -> LumaLoadLog.operationMetrics(active.handle(), metrics));
        LumaMod.LOGGER.warn(
                "Cancelled active world operation {} for project {} during server shutdown",
                active.handle().label(),
                active.handle().projectId()
        );
        LumaLoadLog.event(
                "world-op",
                "cancelled-server-stop",
                "label=" + active.handle().label()
                        + ", projectId=" + active.handle().projectId()
                        + ", stage=" + active.snapshot().stage()
        );
    }

    private void tryCompleteBeforeShutdown(
            String serverKey,
            MinecraftServer server,
            WorldOperationManager.ActiveOperation operation
    ) {
        long deadlineNanos = System.nanoTime() + SERVER_STOP_LIGHT_REFRESH_GRACE.toNanos();
        WorldApplyBudget budget = this.budgetPlanner.plan(1.0D, MAX_ADAPTIVE_SCALE, WorldApplyProfile.MAXIMUM);
        LumaDiagnosticsLog.lightEvent(
                "server-stop-drain-start",
                "label=" + operation.handle().label()
                        + ", operationId=" + operation.handle().id()
                        + ", projectId=" + operation.handle().projectId()
        );
        while (System.nanoTime() < deadlineNanos) {
            if (this.lifecycle.active(serverKey) != operation) {
                return;
            }
            try {
                if (operation.advance(budget, deadlineNanos)) {
                    this.operationCompleter.complete(server, operation);
                    LumaDiagnosticsLog.lightEvent(
                            "server-stop-drain-complete",
                            "label=" + operation.handle().label()
                                    + ", operationId=" + operation.handle().id()
                                    + ", projectId=" + operation.handle().projectId()
                    );
                    return;
                }
            } catch (Exception exception) {
                operation.fail(exception);
                TelemetryService.getInstance().recordOperationFailed(operation.handle(), operation.snapshot(), exception);
                this.operationCompleter.complete(server, operation);
                LumaMod.LOGGER.warn(
                        "Light refresh operation {} failed during server shutdown drain",
                        operation.handle().id(),
                        exception
                );
                return;
            }
            if (!this.pauseServerStopDrain()) {
                break;
            }
        }
        LumaDiagnosticsLog.lightEvent(
                "server-stop-drain-timeout",
                "label=" + operation.handle().label()
                        + ", operationId=" + operation.handle().id()
                        + ", projectId=" + operation.handle().projectId()
                        + ", stage=" + operation.snapshot().stage()
        );
    }

    private boolean pauseServerStopDrain() {
        try {
            Thread.sleep(SERVER_STOP_LIGHT_REFRESH_PAUSE_MILLIS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    interface OperationCompleter {

        void complete(MinecraftServer server, WorldOperationManager.ActiveOperation operation);
    }
}
