package io.github.lumi.telemetry;

import io.github.lumi.LumiMod;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

/** Bounded asynchronous diagnostic queue with no world-data inputs. */
public final class TelemetryService {
    private static final int CAPACITY = 200;
    private static final int BATCH_SIZE = 25;
    private final TelemetrySettingsRepository settingsRepository;
    private final TelemetrySpoolRepository spoolRepository;
    private final Executor executor;
    private final Predicate<List<TelemetryEvent>> sender;
    private final String lumiVersion;
    private final String minecraftVersion;
    private final boolean runtimeEnabled;
    private TelemetrySettings settings;
    private List<TelemetryEvent> queue;
    private String lastSendSummary = "never sent";

    TelemetryService(
            TelemetrySettingsRepository settingsRepository,
            TelemetrySpoolRepository spoolRepository,
            Executor executor,
            Predicate<List<TelemetryEvent>> sender,
            String lumiVersion,
            String minecraftVersion) {
        this(settingsRepository, spoolRepository, executor, sender,
                lumiVersion, minecraftVersion, true);
    }

    private TelemetryService(
            TelemetrySettingsRepository settingsRepository,
            TelemetrySpoolRepository spoolRepository,
            Executor executor,
            Predicate<List<TelemetryEvent>> sender,
            String lumiVersion,
            String minecraftVersion,
            boolean runtimeEnabled) {
        this.settingsRepository = settingsRepository;
        this.spoolRepository = spoolRepository;
        this.executor = executor;
        this.sender = sender;
        this.lumiVersion = lumiVersion;
        this.minecraftVersion = minecraftVersion;
        this.runtimeEnabled = runtimeEnabled;
        settings = settingsRepository.load();
        queue = spoolRepository.load();
    }

    public static TelemetryService getInstance() {
        return Holder.INSTANCE;
    }

    public void start() {
        schedule(this::flush);
    }

    public synchronized TelemetrySettings settings() {
        return settings;
    }

    public synchronized int pendingEventCount() {
        return queue.size();
    }

    public synchronized String lastSendSummary() {
        return lastSendSummary;
    }

    public synchronized void setEnabled(boolean enabled) {
        settings = settings.withEnabled(enabled);
        settingsRepository.save(settings);
        if (!enabled) {
            queue = List.of();
            spoolRepository.clear();
        }
    }

    public synchronized void markNoticeSeen() {
        settings = settings.withNoticeSeen();
        settingsRepository.save(settings);
    }

    public synchronized boolean consumeNotice() {
        if (!settings.enabled() || settings.noticeSeen()) {
            return false;
        }
        markNoticeSeen();
        return true;
    }

    public synchronized void clearLocalQueue() {
        queue = List.of();
        spoolRepository.clear();
    }

    public void recordFailure(String operation, String phase, Throwable failure) {
        var payload = new LinkedHashMap<String, String>();
        payload.put("operation", operation);
        payload.put("stage", phase);
        payload.put("failureClass", failure == null ? "" : failure.getClass().getName());
        payload.put("failureFrame", firstLumiFrame(failure));
        record(TelemetryEventType.OPERATION_FAILED, payload);
    }

    public void recordPerformance(
            String operation, String phase, long elapsedNanos, long budgetNanos) {
        record(TelemetryEventType.PERFORMANCE_OUTLIER, java.util.Map.of(
                "operation", operation,
                "stage", phase,
                "elapsedMicros", Long.toString(Math.max(0, elapsedNanos / 1_000)),
                "budgetMicros", Long.toString(Math.max(0, budgetNanos / 1_000))));
    }

    private void record(TelemetryEventType type, java.util.Map<String, String> payload) {
        if (!runtimeEnabled) {
            return;
        }
        schedule(() -> appendAndFlush(new TelemetryEvent(
                UUID.randomUUID().toString(), 1, type, Instant.now().toString(),
                new TelemetryEnvironment(lumiVersion, minecraftVersion), payload)));
    }

    private void schedule(Runnable task) {
        if (!runtimeEnabled) {
            return;
        }
        try {
            executor.execute(task);
        } catch (RuntimeException rejected) {
            LumiMod.LOGGER.warn("Lumi telemetry task was rejected", rejected);
        }
    }

    private void appendAndFlush(TelemetryEvent event) {
        synchronized (this) {
            if (!settings.enabled()) {
                return;
            }
            var updated = new ArrayList<>(queue);
            updated.add(event);
            queue = List.copyOf(updated.subList(
                    Math.max(0, updated.size() - CAPACITY), updated.size()));
            spoolRepository.save(queue);
        }
        flush();
    }

    private void flush() {
        List<TelemetryEvent> batch;
        synchronized (this) {
            if (!settings.enabled() || queue.isEmpty()) {
                return;
            }
            batch = List.copyOf(queue.subList(0, Math.min(BATCH_SIZE, queue.size())));
        }
        boolean sent;
        try {
            sent = sender.test(batch);
        } catch (RuntimeException failed) {
            LumiMod.LOGGER.debug("Lumi telemetry sender failed", failed);
            sent = false;
        }
        synchronized (this) {
            lastSendSummary = (sent ? "ok" : "failed") + " @ " + Instant.now();
            if (!sent) {
                return;
            }
            var sentIds = batch.stream().map(TelemetryEvent::id).collect(
                    java.util.stream.Collectors.toSet());
            queue = queue.stream().filter(event -> !sentIds.contains(event.id())).toList();
            spoolRepository.save(queue);
        }
    }

    private static String firstLumiFrame(Throwable failure) {
        if (failure == null) {
            return "";
        }
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().startsWith("io.github.lumi")) {
                return frame.getClassName() + "#" + frame.getMethodName()
                        + ":" + frame.getLineNumber();
            }
        }
        return "";
    }

    private static final class Holder {
        private static final TelemetryService INSTANCE = createDefault();
    }

    private static TelemetryService createDefault() {
        String version = FabricLoader.getInstance().getModContainer(LumiMod.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        var sender = new TelemetryHttpSender();
        return new TelemetryService(
                new TelemetrySettingsRepository(),
                new TelemetrySpoolRepository(CAPACITY),
                Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, "lumi-telemetry");
                    thread.setDaemon(true);
                    return thread;
                }),
                sender::send,
                version,
                SharedConstants.getCurrentVersion().name(),
                !FabricLoader.getInstance().isDevelopmentEnvironment());
    }
}
