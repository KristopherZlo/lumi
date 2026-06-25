package io.github.luma.client.diagnostics;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * Test-client runtime load sampler for CPU, memory, render pressure, and GPU metrics.
 */
public final class ClientRuntimeLoadSampler implements AutoCloseable {

    private static final String ENABLED_FLAG = "lumi.clientLoadLog";
    private static final String SAMPLE_TICKS_FLAG = "lumi.clientLoadLog.sampleTicks";
    private static final String GPU_SAMPLE_SECONDS_FLAG = "lumi.clientLoadLog.gpuSampleSeconds";
    private static final int DEFAULT_FRAME_WINDOW = 256;

    private final ClientRuntimeLoadLog log = new ClientRuntimeLoadLog();
    private final ClientJvmLoadProbe jvmLoadProbe = new ClientJvmLoadProbe();
    private final ClientFrameTimeWindow frameTimes = new ClientFrameTimeWindow(DEFAULT_FRAME_WINDOW);
    private final ClientGpuMetricsProbe gpuMetricsProbe = new NvidiaSmiClientGpuMetricsProbe();
    private final ExecutorService gpuExecutor = Executors.newSingleThreadExecutor(ClientRuntimeLoadSampler::metricsThread);
    private final int sampleIntervalTicks = Math.max(1, Integer.getInteger(SAMPLE_TICKS_FLAG, 20));
    private final long gpuSampleIntervalNanos = TimeUnit.SECONDS.toNanos(
            Math.max(1L, Long.getLong(GPU_SAMPLE_SECONDS_FLAG, 5L))
    );
    private CompletableFuture<ClientGpuMetrics> pendingGpuMetrics;
    private ClientGpuMetrics latestGpuMetrics = ClientGpuMetrics.unavailable("nvidia-smi", "not-sampled");
    private long lastFrameNanos;
    private long lastGpuRequestNanos;
    private int ticksUntilSample;
    private boolean started;
    private boolean gpuInfoLogged;
    private boolean closed;

    private ClientRuntimeLoadSampler() {
    }

    public static boolean configuredEnabled() {
        return Boolean.getBoolean(ENABLED_FLAG);
    }

    public static ClientRuntimeLoadSampler getInstance() {
        return Holder.INSTANCE;
    }

    public boolean enabled() {
        return this.log.enabled();
    }

    public void tick(Minecraft client) {
        if (!this.enabled() || this.closed) {
            return;
        }
        this.startIfNeeded();
        long now = System.nanoTime();
        this.completeGpuSample();
        this.requestGpuSample(now);
        this.ticksUntilSample -= 1;
        if (this.ticksUntilSample > 0) {
            return;
        }
        this.ticksUntilSample = this.sampleIntervalTicks;
        this.log.sample(this.snapshot(client, now));
    }

    public void onWorldRender(WorldRenderContext context) {
        if (!this.enabled() || this.closed) {
            return;
        }
        this.startIfNeeded();
        this.logGpuInfoIfNeeded();
        long now = System.nanoTime();
        if (this.lastFrameNanos > 0L) {
            this.frameTimes.record(now - this.lastFrameNanos);
        }
        this.lastFrameNanos = now;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.gpuMetricsProbe.close();
        this.gpuExecutor.shutdownNow();
        this.log.close();
    }

    private void startIfNeeded() {
        if (this.started) {
            return;
        }
        this.started = true;
        this.ticksUntilSample = this.sampleIntervalTicks;
        this.log.sessionStart(this.sampleIntervalTicks, TimeUnit.NANOSECONDS.toSeconds(this.gpuSampleIntervalNanos));
    }

    private ClientRuntimeLoadSnapshot snapshot(Minecraft client, long nowNanos) {
        ClientJvmLoadProbe.CpuStats cpu = this.jvmLoadProbe.cpuStats(nowNanos);
        ClientJvmLoadProbe.MemoryStats memory = this.jvmLoadProbe.memoryStats();
        Window window = client == null ? null : client.getWindow();
        return new ClientRuntimeLoadSnapshot(
                Instant.now(),
                this.dimension(client),
                this.screen(client),
                window == null ? 0 : window.getWidth(),
                window == null ? 0 : window.getHeight(),
                window == null ? 0 : window.getGuiScaledWidth(),
                window == null ? 0 : window.getGuiScaledHeight(),
                this.frameTimes.snapshotAndReset(),
                cpu.processCpuLoadPercent(),
                cpu.systemCpuLoadPercent(),
                cpu.processCpuCores(),
                cpu.availableProcessors(),
                cpu.liveThreads(),
                memory.heapUsedMiB(),
                memory.heapCommittedMiB(),
                memory.heapMaxMiB(),
                memory.nonHeapUsedMiB(),
                memory.directBufferUsedMiB(),
                memory.mappedBufferUsedMiB(),
                memory.gcCount(),
                memory.gcTimeMillis(),
                this.latestGpuMetrics
        );
    }

    private void requestGpuSample(long nowNanos) {
        if (this.pendingGpuMetrics != null
                || nowNanos - this.lastGpuRequestNanos < this.gpuSampleIntervalNanos
                || this.gpuExecutor.isShutdown()) {
            return;
        }
        this.lastGpuRequestNanos = nowNanos;
        this.pendingGpuMetrics = CompletableFuture.supplyAsync(this.gpuMetricsProbe::sample, this.gpuExecutor);
    }

    private void completeGpuSample() {
        if (this.pendingGpuMetrics == null || !this.pendingGpuMetrics.isDone()) {
            return;
        }
        try {
            this.latestGpuMetrics = this.pendingGpuMetrics.join();
        } catch (RuntimeException exception) {
            this.latestGpuMetrics = ClientGpuMetrics.unavailable("nvidia-smi", exception.getClass().getSimpleName());
        } finally {
            this.pendingGpuMetrics = null;
        }
    }

    private void logGpuInfoIfNeeded() {
        if (this.gpuInfoLogged) {
            return;
        }
        this.gpuInfoLogged = true;
        this.log.gpuInfo(
                GL11.glGetString(GL11.GL_VENDOR),
                GL11.glGetString(GL11.GL_RENDERER),
                GL11.glGetString(GL11.GL_VERSION),
                this.latestGpuMetrics.provider()
        );
    }

    private String dimension(Minecraft client) {
        if (client == null || client.level == null) {
            return "none";
        }
        return client.level.dimension().identifier().toString();
    }

    private String screen(Minecraft client) {
        if (client == null || client.screen == null) {
            return "none";
        }
        return client.screen.getClass().getSimpleName();
    }

    private static Thread metricsThread(Runnable task) {
        Thread thread = new Thread(task, "lumi-client-gpu-metrics");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    }

    private static final class Holder {

        private static final ClientRuntimeLoadSampler INSTANCE = new ClientRuntimeLoadSampler();

        private Holder() {
        }
    }
}
