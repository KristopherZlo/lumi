package io.github.luma.telemetry;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

final class TelemetryEventFactory {

    private static final int FAILURE_TRACE_LIMIT = 8;
    private static final int FAILURE_CAUSE_LIMIT = 4;

    private final TelemetryEnvironmentProvider environmentProvider;
    private final Supplier<Instant> clock;

    TelemetryEventFactory(TelemetryEnvironmentProvider environmentProvider, Supplier<Instant> clock) {
        this.environmentProvider = environmentProvider;
        this.clock = clock;
    }

    TelemetryEvent operationRejected(TelemetrySettings settings, String action, String statusKey, Throwable failure) {
        Map<String, String> payload = new LinkedHashMap<>();
        put(payload, "action", action);
        put(payload, "statusKey", statusKey);
        putFailure(payload, failure);
        return this.event(settings, TelemetryEventType.OPERATION_REJECTED, payload);
    }

    TelemetryEvent operationFailed(
            TelemetrySettings settings,
            OperationHandle handle,
            OperationSnapshot snapshot,
            Throwable failure
    ) {
        Map<String, String> payload = new LinkedHashMap<>();
        put(payload, "operation", handle == null ? "" : handle.label());
        put(payload, "stage", snapshot == null || snapshot.stage() == null ? "" : snapshot.stage().name());
        if (snapshot != null && snapshot.progress() != null) {
            OperationProgress progress = snapshot.progress();
            put(payload, "completedUnits", Integer.toString(Math.max(0, progress.completedUnits())));
            put(payload, "totalUnits", Integer.toString(Math.max(0, progress.totalUnits())));
            put(payload, "unitLabel", progress.unitLabel());
        }
        if (handle != null && handle.startedAt() != null) {
            long durationMs = Duration.between(handle.startedAt(), this.clock.get()).toMillis();
            put(payload, "durationMs", Long.toString(Math.max(0L, durationMs)));
        }
        putFailure(payload, failure);
        return this.event(settings, TelemetryEventType.OPERATION_FAILED, payload);
    }

    TelemetryEvent clientCrashCandidate(TelemetrySettings settings, Throwable failure) {
        Map<String, String> payload = new LinkedHashMap<>();
        putFailure(payload, failure);
        return this.event(settings, TelemetryEventType.CLIENT_CRASH_CANDIDATE, payload);
    }

    TelemetryEvent renderOverlayDisabled(TelemetrySettings settings, String overlayName, Throwable failure) {
        Map<String, String> payload = new LinkedHashMap<>();
        put(payload, "overlay", overlayName);
        putFailure(payload, failure);
        return this.event(settings, TelemetryEventType.RENDER_OVERLAY_DISABLED, payload);
    }

    TelemetryEvent performanceOutlier(
            TelemetrySettings settings,
            String operationLabel,
            long elapsedNanos,
            long budgetNanos,
            String stage
    ) {
        Map<String, String> payload = new LinkedHashMap<>();
        put(payload, "operation", operationLabel);
        put(payload, "stage", stage);
        put(payload, "elapsedMicros", Long.toString(Math.max(0L, elapsedNanos / 1_000L)));
        put(payload, "budgetMicros", Long.toString(Math.max(0L, budgetNanos / 1_000L)));
        return this.event(settings, TelemetryEventType.PERFORMANCE_OUTLIER, payload);
    }

    private TelemetryEvent event(TelemetrySettings settings, TelemetryEventType type, Map<String, String> payload) {
        return new TelemetryEvent(
                UUID.randomUUID().toString(),
                TelemetryEvent.SCHEMA_VERSION,
                type,
                this.clock.get(),
                settings.installationId(),
                this.environmentProvider.current(),
                fingerprint(type, payload),
                payload
        );
    }

    private static void putFailure(Map<String, String> payload, Throwable failure) {
        put(payload, "failureClass", failure == null ? "" : failure.getClass().getName());
        put(payload, "failureFrame", firstLumiFrame(failure));
        put(payload, "failureTrace", lumiTrace(failure));
        put(payload, "failureCauseChain", causeChain(failure));
    }

    private static void put(Map<String, String> payload, String key, String value) {
        payload.put(key, value == null ? "" : value);
    }

    private static String fingerprint(TelemetryEventType type, Map<String, String> payload) {
        StringBuilder source = new StringBuilder(type.name());
        payload.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> source.append('\n').append(entry.getKey()).append('=').append(entry.getValue()));
        return Integer.toHexString(source.toString().hashCode());
    }

    private static String firstLumiFrame(Throwable failure) {
        if (failure == null) {
            return "";
        }
        for (StackTraceElement element : failure.getStackTrace()) {
            if (element.getClassName().startsWith("io.github.luma")) {
                return formatFrame(element);
            }
        }
        return "";
    }

    private static String lumiTrace(Throwable failure) {
        if (failure == null) {
            return "";
        }
        StringBuilder trace = new StringBuilder();
        int added = 0;
        for (StackTraceElement element : failure.getStackTrace()) {
            if (!element.getClassName().startsWith("io.github.luma")) {
                continue;
            }
            if (trace.length() > 0) {
                trace.append('\n');
            }
            trace.append(formatFrame(element));
            added++;
            if (added >= FAILURE_TRACE_LIMIT) {
                break;
            }
        }
        return trace.toString();
    }

    private static String causeChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        Throwable cursor = failure;
        int added = 0;
        while (cursor != null && added < FAILURE_CAUSE_LIMIT) {
            if (chain.length() > 0) {
                chain.append(" -> ");
            }
            chain.append(cursor.getClass().getName());
            Throwable next = cursor.getCause();
            if (next == cursor) {
                break;
            }
            cursor = next;
            added++;
        }
        return chain.toString();
    }

    private static String formatFrame(StackTraceElement element) {
        String frame = element.getClassName() + "#" + element.getMethodName();
        return element.getLineNumber() < 0 ? frame : frame + ":" + element.getLineNumber();
    }
}
