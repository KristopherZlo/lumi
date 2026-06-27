package io.github.luma.telemetry;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.minecraft.testing.RuntimeTestingConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class TelemetryService {

    public static final String DEFAULT_ENDPOINT_URL = "https://lumi.zloyxp.cc/v1/events/batch";
    private static final int DEFAULT_SPOOL_CAPACITY = 200;
    private static final int DEFAULT_BATCH_SIZE = 25;

    private final Object lock = new Object();
    private final TelemetrySettingsRepository settingsRepository;
    private final TelemetrySpoolRepository spoolRepository;
    private final TelemetryBatchSender sender;
    private final TelemetryEventFactory eventFactory;
    private final TelemetryEndpointPolicy endpointPolicy = new TelemetryEndpointPolicy();
    private final Executor executor;
    private final Supplier<Instant> clock;
    private final Supplier<String> installationIds;
    private final int batchSize;
    private final boolean transportEnabled;
    private TelemetrySettings settings;
    private List<TelemetryEvent> queue;
    private boolean clientRuntimeEnabled;
    private TelemetrySendResult lastSendResult = TelemetrySendResult.success(0);
    private Instant lastSendAt;

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
                true,
                settings
        );
    }

    public void enableClientRuntime() {
        if (!this.transportEnabled) {
            return;
        }
        synchronized (this.lock) {
            this.clientRuntimeEnabled = true;
            this.rotateSettingsIfNeeded();
        }
        this.schedule(this::recordInstallationSeen);
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

    public String lastSendSummary() {
        synchronized (this.lock) {
            if (this.lastSendAt == null) {
                return "never sent";
            }
            String status = this.lastSendResult.success() ? "ok" : "failed";
            String detail = this.lastSendResult.detail().isBlank() ? "" : " " + this.lastSendResult.detail();
            return status + " @" + this.lastSendAt + detail;
        }
    }

    public void setEnabled(boolean enabled) {
        if (!this.transportEnabled) {
            return;
        }
        synchronized (this.lock) {
            this.settings = this.settings.withEnabled(enabled);
            this.saveSettings();
            if (!enabled) {
                this.queue = List.of();
                this.spoolRepository.clear();
            }
        }
    }

    public void markNoticeSeen() {
        if (!this.transportEnabled) {
            return;
        }
        synchronized (this.lock) {
            this.settings = this.settings.withNoticeSeen();
            this.saveSettings();
        }
    }

    public void clearLocalQueue() {
        if (!this.transportEnabled) {
            return;
        }
        synchronized (this.lock) {
            this.queue = List.of();
            this.spoolRepository.clear();
        }
    }

    public void recordOperationRejected(String action, String statusKey, Throwable failure) {
        if (!this.transportEnabled) {
            return;
        }
        this.schedule(() -> this.record(settings -> this.eventFactory.operationRejected(settings, action, statusKey, failure)));
    }

    public void recordOperationFailed(OperationHandle handle, OperationSnapshot snapshot, Throwable failure) {
        if (!this.transportEnabled) {
            return;
        }
        this.schedule(() -> this.record(settings -> this.eventFactory.operationFailed(settings, handle, snapshot, failure)));
    }

    public void recordClientCrashCandidate(Throwable failure) {
        if (!this.transportEnabled) {
            return;
        }
        this.schedule(() -> this.record(settings -> this.eventFactory.clientCrashCandidate(settings, failure)));
    }

    public void recordRenderOverlayDisabled(String overlayName, Throwable failure) {
        if (!this.transportEnabled) {
            return;
        }
        this.schedule(() -> this.record(settings -> this.eventFactory.renderOverlayDisabled(settings, overlayName, failure)));
    }

    public void recordPerformanceOutlier(String operationLabel, long elapsedNanos, long budgetNanos, String stage) {
        if (!this.transportEnabled) {
            return;
        }
        this.schedule(() -> this.record(settings ->
                this.eventFactory.performanceOutlier(settings, operationLabel, elapsedNanos, budgetNanos, stage)));
    }

    public void flushAsync() {
        if (!this.transportEnabled) {
            return;
        }
        this.schedule(this::flushCurrent);
    }

    public void flushNow() {
        if (!this.transportEnabled) {
            return;
        }
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
            boolean transportEnabled,
            boolean clientRuntimeEnabled,
            TelemetrySettings initialSettings
    ) {
        this.settingsRepository = settingsRepository;
        this.spoolRepository = spoolRepository;
        this.sender = sender;
        this.eventFactory = new TelemetryEventFactory(environmentProvider, clock);
        this.executor = executor;
        this.clock = clock;
        this.installationIds = installationIds;
        this.batchSize = Math.max(1, batchSize);
        this.transportEnabled = transportEnabled;
        this.clientRuntimeEnabled = clientRuntimeEnabled;
        this.settings = initialSettings == null ? settingsRepository.load() : initialSettings;
        this.queue = this.transportEnabled ? this.spoolRepository.load() : List.of();
    }

    private static TelemetryService createDefault() {
        Supplier<String> ids = TelemetryService::randomInstallationId;
        boolean transportEnabled = !RuntimeTestingConfig.load().enabled();
        TelemetrySettingsRepository settingsRepository;
        TelemetrySpoolRepository spoolRepository;
        if (transportEnabled) {
            try {
                settingsRepository = new TelemetrySettingsRepository(DEFAULT_ENDPOINT_URL, ids);
                spoolRepository = new TelemetrySpoolRepository(DEFAULT_SPOOL_CAPACITY);
            } catch (RuntimeException exception) {
                transportEnabled = false;
                settingsRepository = new TelemetrySettingsRepository(null, "", ids, true);
                spoolRepository = new TelemetrySpoolRepository(null, DEFAULT_SPOOL_CAPACITY);
            }
        } else {
            settingsRepository = new TelemetrySettingsRepository(null, "", ids, true);
            spoolRepository = new TelemetrySpoolRepository(null, DEFAULT_SPOOL_CAPACITY);
        }
        return new TelemetryService(
                settingsRepository,
                spoolRepository,
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
                transportEnabled,
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

    private void record(TelemetryEventBuilder eventBuilder) {
        synchronized (this.lock) {
            if (!this.readyToSendLocked()) {
                this.clearLocalQueueLocked();
                return;
            }
            this.rotateSettingsIfNeeded();
            if (!this.endpointPolicy.sendable(this.settings.endpointUrl())) {
                this.clearLocalQueueLocked();
                return;
            }
            List<TelemetryEvent> updated = new ArrayList<>(this.queue);
            updated.add(eventBuilder.create(this.settings));
            this.queue = List.copyOf(updated);
            this.spoolRepository.save(this.queue);
        }
        this.flushCurrent();
    }

    private void recordInstallationSeen() {
        synchronized (this.lock) {
            if (!this.readyToSendLocked()) {
                this.clearLocalQueueLocked();
                return;
            }
            if (this.settings.installationReported()) {
                return;
            }
            this.rotateSettingsIfNeeded();
            if (!this.endpointPolicy.sendable(this.settings.endpointUrl())) {
                this.clearLocalQueueLocked();
                return;
            }
            List<TelemetryEvent> updated = new ArrayList<>(this.queue);
            updated.add(this.eventFactory.installationSeen(this.settings));
            this.queue = List.copyOf(updated);
            this.settings = this.settings.withInstallationReported();
            this.saveSettings();
            this.spoolRepository.save(this.queue);
        }
        this.flushCurrent();
    }

    private void flushCurrent() {
        List<TelemetryEvent> batch;
        TelemetrySettings currentSettings;
        synchronized (this.lock) {
            if (!this.readyToSendLocked()) {
                this.clearLocalQueueLocked();
                return;
            }
            this.rotateSettingsIfNeeded();
            if (!this.endpointPolicy.sendable(this.settings.endpointUrl())) {
                this.clearLocalQueueLocked();
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
            this.lastSendResult = result;
            this.lastSendAt = this.clock.get();
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

    private boolean readyToSendLocked() {
        return this.transportEnabled && this.clientRuntimeEnabled && this.settings.enabled();
    }

    private void clearLocalQueueLocked() {
        this.queue = List.of();
        this.spoolRepository.clear();
    }

    private void schedule(Runnable task) {
        try {
            this.executor.execute(task);
        } catch (RuntimeException exception) {
            LumaMod.LOGGER.warn("Lumi telemetry task was rejected", exception);
        }
    }

    @FunctionalInterface
    private interface TelemetryEventBuilder {

        TelemetryEvent create(TelemetrySettings settings);
    }
}
