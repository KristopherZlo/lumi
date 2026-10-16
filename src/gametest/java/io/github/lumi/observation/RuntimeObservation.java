package io.github.lumi.observation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Writes bounded, wall-clock samples off both Minecraft tick threads. */
final class RuntimeObservation {
    record ClientState(int fps, boolean loaded, boolean paused, String screen, boolean simulationFrozen) {}

    private final Properties metadata;
    private final Supplier<ClientState> client;
    private final LongSupplier ticks;
    private final Runnable finish;
    private final Path directory;
    private final BufferedWriter samples;
    private final int warmup = seconds("warmupSeconds", 120);
    private final int duration = seconds("seconds", 300);
    private final long started = System.nanoTime();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Lumi-ordinary-observation");
        thread.setDaemon(true);
        return thread;
    });
    private long loaded;
    private int measured;
    private int paused;
    private boolean completed;

    RuntimeObservation(Properties metadata, Supplier<ClientState> client,
            LongSupplier ticks, Runnable finish) throws IOException {
        this.metadata = metadata;
        this.client = client;
        this.ticks = ticks;
        this.finish = finish;
        directory = Path.of(System.getProperty("lumi.observation.output"));
        Files.createDirectories(directory);
        samples = Files.newBufferedWriter(directory.resolve("samples.csv"), StandardOpenOption.CREATE_NEW);
        samples.write("utc,elapsedNanos,phase,processCpuNanos,heapUsedBytes,heapCommittedBytes,fps,serverTicks,paused,screen,simulationFrozen\n");
        metadata.setProperty("status", "running");
        metadata.setProperty("warmupSeconds", Integer.toString(warmup));
        metadata.setProperty("seconds", Integer.toString(duration));
        writeMetadata();
    }

    void start() {
        worker.scheduleAtFixedRate(this::sample, 1, 1, TimeUnit.SECONDS);
    }

    private void sample() {
        try {
            long now = System.nanoTime();
            ClientState state = client.get();
            if (loaded == 0) {
                if (now - started > TimeUnit.MINUTES.toNanos(5)) {
                    throw new IllegalStateException("Quick Play did not open an integrated world");
                }
                if (!state.loaded()) return;
                loaded = now;
            }
            if (!state.loaded()) throw new IllegalStateException("Observation world closed before completion");
            long elapsed = now - loaded;
            if (elapsed >= TimeUnit.SECONDS.toNanos((long) warmup + duration)) {
                if (measured < Math.max(1, duration * 9 / 10) || paused != 0) {
                    throw new IllegalStateException("Insufficient ordinary unpaused samples: " + measured + "/" + paused);
                }
                complete(null);
                return;
            }
            boolean measuring = elapsed >= TimeUnit.SECONDS.toNanos(warmup);
            if (measuring) {
                measured++;
                if (state.paused() || state.simulationFrozen()) paused++;
            }
            var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            samples.write(Instant.now() + "," + elapsed + "," + (measuring ? "measure" : "warmup")
                    + "," + os.getProcessCpuTime() + "," + heap.getUsed() + "," + heap.getCommitted()
                    + "," + state.fps() + "," + ticks.getAsLong() + "," + state.paused() + "," + state.screen()
                    + "," + state.simulationFrozen() + "\n");
            samples.flush();
        } catch (Exception failed) {
            complete(failed);
        }
    }

    private void complete(Exception failure) {
        if (completed) return;
        completed = true;
        worker.shutdown();
        metadata.setProperty("status", failure == null ? "passed" : "failed");
        metadata.setProperty("samples", Integer.toString(measured));
        metadata.setProperty("pausedSamples", Integer.toString(paused));
        metadata.setProperty("lastScreen", client.get().screen());
        if (failure != null) metadata.setProperty("failure", failure.toString());
        try {
            samples.close();
            writeMetadata();
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot persist ordinary observation", failed);
        } finally {
            finish.run();
        }
    }

    private void writeMetadata() throws IOException {
        try (var output = Files.newBufferedWriter(directory.resolve("observation.properties"))) {
            metadata.store(output, "Ordinary client observation; no GameTest synchronization");
        }
    }

    private static int seconds(String name, int fallback) {
        int value = Integer.parseInt(System.getProperty("lumi.observation." + name, Integer.toString(fallback)));
        int minimum = name.equals("seconds") ? 3 : 1;
        if (value < minimum || value > 86400) throw new IllegalArgumentException("Invalid observation " + name);
        return value;
    }
}
