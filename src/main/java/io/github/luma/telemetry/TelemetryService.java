package io.github.luma.telemetry;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class TelemetryService {

    public static final String DEFAULT_ENDPOINT_URL = "https://telemetry.lumimod.example/v1/events/batch";
    private static final int DEFAULT_SPOOL_CAPACITY = 200;
    private static final int DEFAULT_BATCH_SIZE = 25;

    private final Object lock = new Object();
    private final TelemetrySettingsRepository settingsRepository;
    private final TelemetrySpoolRepository spoolRepository;
    private final TelemetryEnvironmentProvider environmentProvider;
    private final TelemetryBatchSender sender;
    private final TelemetrySanitizer sanitizer = new TelemetrySanitizer();
    private final Executor executor;
    private final Supplier<Instant> clock;
    private final Supplier<String> installationIds;
    private final int batchSize;
    private TelemetrySettings settings;
    private List<TelemetryEvent> queue;
    private boolean clientRuntimeEnabled;

    public static TelemetryService getInstance() {
        return Holder.INSTANCE;
    }

    public static TelemetryService testing(
            TelemetrySettings settings,
            TelemetrySpoolRepository spoolRepository,
            TelemetryEnvironmentProvider environmentProvider,
            Executor executor,
            TelemetryBatchSender sender
    ) {
        return new TelemetryService(
                null,
                spoolRepository,
                environmentProvider,
                sender,
                executor,
                Instant::now,
                TelemetryService::randomInstallationId,
                DEFAULT_BATCH_SIZE,
                true,
                settings
        );
    }

    public void enableClientRuntime() {
        synchronized (this.lock) {
            this.clientRuntimeEnabled = true;
            this.rotateSettingsIfNeeded();
        }
        this.schedule(this::flushCurrent);
    }

    public TelemetrySettings settings() {
        synchronized (this.lock) {
            return this.settings;
        }
    }

    public int pendingEventCount() {
        synchronized (this.lock) {
            return this.queue.size();
        }
    }

    public void setEnabled(boolean enabled) {
        this.schedule(() -> {
            synchronized (this.lock) {
                this.settings = this.settings.withEnabled(enabled);
                this.saveSettings();
                if (!enabled) {
                    this.queue = List.of();
                    this.spoolRepository.clear();
                }
            }
        });
    }

    public void markNoticeSeen() {
        this.schedule(() -> {
            synchronized (this.lock) {
                this.settings = this.settings.withNoticeSeen();
                this.saveSettings();
            }
        });
    }

    public void clearLocalQueue() {
        this.schedule(() -> {
            synchronized (this.lock) {
                this.queue = List.of();
                this.spoolRepository.clear();
            }
        });
    }

    public void recordOperationRejected(String action, String statusKey, Throwable failure) {
        this.schedule(() -> this.record(TelemetryEventType.OPERATION_REJECTED, failure, Map.of(
                "action", safe(action),
                "statusKey", safe(statusKey),
                "failure", this.sanitizer.sanitizeText(failureMessage(failure))
        )));
    }

    public void recordOperationFailed(OperationHandle handle, OperationSnapshot snapshot, Throwable failure) {
        this.schedule(() -> {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("operation", safe(handle == null ? "" : handle.label()));
            payload.put("stage", safe(snapshot == null || snapshot.stage() == null ? "" : snapshot.stage().name()));
            payload.put("detail", this.sanitizer.sanitizeText(snapshot == null ? "" : snapshot.detail()));
            if (snapshot != null && snapshot.progress() != null) {
                payload.put("completedUnits", Integer.toString(snapshot.progress().completedUnits()));
                payload.put("totalUnits", Integer.toString(snapshot.progress().totalUnits()));
                payload.put("unitLabel", safe(snapshot.progress().unitLabel()));
            }
            if (handle != null && handle.startedAt() != null) {
                payload.put("durationMs", Long.toString(Math.max(0L, Duration.between(handle.startedAt(), this.clock.get()).toMillis())));
            }
            payload.put("failure", this.sanitizer.sanitizeText(failureMessage(failure)));
            this.record(TelemetryEventType.OPERATION_FAILED, failure, payload);
        });
    }

    public void recordClientCrashCandidate(Throwable failure) {
        this.schedule(() -> this.record(TelemetryEventType.CLIENT_CRASH_CANDIDATE, failure, Map.of(
                "failure", this.sanitizer.sanitizeText(failureMessage(failure))
        )));
    }

    public void recordRenderOverlayDisabled(String overlayName, Throwable failure) {
        this.schedule(() -> this.record(TelemetryEventType.RENDER_OVERLAY_DISABLED, failure, Map.of(
                "overlay", safe(overlayName),
                "failure", this.sanitizer.sanitizeText(failureMessage(failure))
        )));
    }

    public void recordPerformanceOutlier(String operationLabel, long elapsedNanos, long budgetNanos, String stage) {
        this.schedule(() -> this.record(TelemetryEventType.PERFORMANCE_OUTLIER, null, Map.of(
                "operation", safe(operationLabel),
                "stage", safe(stage),
                "elapsedMicros", Long.toString(Math.max(0L, elapsedNanos / 1_000L)),
                "budgetMicros", Long.toString(Math.max(0L, budgetNanos / 1_000L))
        )));
    }

    public void flushAsync() {
        this.schedule(this::flushCurrent);
    }

    public void flushNow() {
        this.flushCurrent();
    }

    private TelemetryService(
            TelemetrySettingsRepository settingsRepository,
            TelemetrySpoolRepository spoolRepository,
            TelemetryEnvironmentProvider environmentProvider,
            TelemetryBatchSender sender,
            Executor executor,
            Supplier<Instant> clock,
            Supplier<String> installationIds,
            int batchSize,
            boolean clientRuntimeEnabled,
            TelemetrySettings initialSettings
    ) {
        this.settingsRepository = settingsRepository;
        this.spoolRepository = spoolRepository;
        this.environmentProvider = environmentProvider;
        this.sender = sender;
        this.executor = executor;
        this.clock = clock;
        this.installationIds = installationIds;
        this.batchSize = Math.max(1, batchSize);
        this.clientRuntimeEnabled = clientRuntimeEnabled;
        this.settings = initialSettings == null ? settingsRepository.load() : initialSettings;
        this.queue = this.spoolRepository.load();
    }

    private static TelemetryService createDefault() {
        Supplier<String> ids = TelemetryService::randomInstallationId;
        return new TelemetryService(
                new TelemetrySettingsRepository(DEFAULT_ENDPOINT_URL, ids),
                new TelemetrySpoolRepository(DEFAULT_SPOOL_CAPACITY),
                new TelemetryEnvironmentProvider.Fabric(),
                new TelemetrySender(new JavaNetTelemetryHttpTransport(Duration.ofSeconds(3)), Duration.ofSeconds(3)),
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "lumi-telemetry");
                    thread.setDaemon(true);
                    return thread;
                }),
                Instant::now,
                ids,
                DEFAULT_BATCH_SIZE,
                false,
                null
        );
    }

    private static String randomInstallationId() {
        return UUID.randomUUID().toString();
    }

    private static final class Holder {

        private static final TelemetryService INSTANCE = createDefault();
    }

    private void record(TelemetryEventType type, Throwable failure, Map<String, String> payload) {
        synchronized (this.lock) {
            if (!this.clientRuntimeEnabled || !this.settings.enabled()) {
                this.queue = List.of();
                this.spoolRepository.clear();
                return;
            }
            this.rotateSettingsIfNeeded();
            List<TelemetryEvent> updated = new ArrayList<>(this.queue);
            updated.add(new TelemetryEvent(
                    UUID.randomUUID().toString(),
                    TelemetryEvent.SCHEMA_VERSION,
                    type,
                    this.clock.get(),
                    this.settings.installationId(),
                    this.environmentProvider.current(),
                    this.fingerprint(type, failure, payload),
                    payload
            ));
            this.queue = List.copyOf(updated);
            this.spoolRepository.save(this.queue);
        }
        this.flushCurrent();
    }

    private void flushCurrent() {
        List<TelemetryEvent> batch;
        TelemetrySettings currentSettings;
        synchronized (this.lock) {
            if (!this.clientRuntimeEnabled || !this.settings.enabled()) {
                this.queue = List.of();
                this.spoolRepository.clear();
                return;
            }
            if (this.queue.isEmpty()) {
                return;
            }
            currentSettings = this.settings;
            batch = List.copyOf(this.queue.subList(0, Math.min(this.batchSize, this.queue.size())));
        }

        TelemetrySendResult result = this.sender.send(currentSettings.endpointUrl(), batch);
        synchronized (this.lock) {
            if (!result.success()) {
                this.spoolRepository.save(this.queue);
                return;
            }
            int accepted = Math.min(result.acceptedEvents(), batch.size());
            List<TelemetryEvent> updated = new ArrayList<>(this.queue);
            updated.subList(0, accepted).clear();
            this.queue = List.copyOf(updated);
            this.spoolRepository.save(this.queue);
        }
    }

    private void rotateSettingsIfNeeded() {
        TelemetrySettings rotated = this.settings.rotateIfExpired(this.clock.get(), this.installationIds);
        if (!rotated.equals(this.settings)) {
            this.settings = rotated;
            this.saveSettings();
        }
    }

    private void saveSettings() {
        if (this.settingsRepository != null) {
            this.settingsRepository.save(this.settings);
        }
    }

    private String fingerprint(TelemetryEventType type, Throwable failure, Map<String, String> payload) {
        String source = type.name() + ":" + (failure == null ? "" : failure.getClass().getName()) + ":" + firstLumiFrame(failure);
        if (failure == null && payload != null) {
            source = source + ":" + payload;
        }
        return Integer.toHexString(source.hashCode());
    }

    private static String firstLumiFrame(Throwable failure) {
        if (failure == null) {
            return "";
        }
        for (StackTraceElement element : failure.getStackTrace()) {
            if (element.getClassName().startsWith("io.github.luma")) {
                return element.getClassName() + "#" + element.getMethodName();
            }
        }
        return "";
    }

    private static String failureMessage(Throwable failure) {
        if (failure == null) {
            return "";
        }
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private void schedule(Runnable task) {
        try {
            this.executor.execute(task);
        } catch (RuntimeException exception) {
            LumaMod.LOGGER.warn("Lumi telemetry task was rejected", exception);
        }
    }
}
