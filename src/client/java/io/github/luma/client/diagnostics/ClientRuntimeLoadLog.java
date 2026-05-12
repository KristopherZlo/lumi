package io.github.luma.client.diagnostics;

import io.github.luma.LumaMod;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;

final class ClientRuntimeLoadLog {

    private static final String ENABLED_FLAG = "lumi.clientLoadLog";
    private static final String PATH_FLAG = "lumi.clientLoadLog.path";
    private static final boolean ENABLED = Boolean.getBoolean(ENABLED_FLAG);
    private static final Object LOCK = new Object();

    private BufferedWriter writer;
    private boolean sinkFailed;

    boolean enabled() {
        return ENABLED;
    }

    Path configuredPath() {
        Path configured = Path.of(System.getProperty(PATH_FLAG, "logs/lumi-client-load.log"));
        return configured.isAbsolute() ? configured : Path.of("").toAbsolutePath().resolve(configured).normalize();
    }

    void sessionStart(int sampleIntervalTicks, long gpuSampleSeconds) {
        if (!ENABLED) {
            return;
        }
        this.writeLine("time=" + quote(Instant.now().toString())
                + " type=\"session-start\""
                + " sampleIntervalTicks=" + sampleIntervalTicks
                + " gpuSampleSeconds=" + gpuSampleSeconds);
    }

    void gpuInfo(String vendor, String renderer, String version, String metricsProvider) {
        if (!ENABLED) {
            return;
        }
        this.writeLine("time=" + quote(Instant.now().toString())
                + " type=\"gpu-info\""
                + " vendor=" + quote(vendor)
                + " renderer=" + quote(renderer)
                + " version=" + quote(version)
                + " metricsProvider=" + quote(metricsProvider));
    }

    void sample(ClientRuntimeLoadSnapshot snapshot) {
        if (!ENABLED || snapshot == null) {
            return;
        }
        this.writeLine(sampleLine(snapshot));
    }

    void close() {
        if (!ENABLED) {
            return;
        }
        synchronized (LOCK) {
            this.writeLineLocked("time=" + quote(Instant.now().toString()) + " type=\"session-stop\"");
            if (this.writer == null) {
                return;
            }
            try {
                this.writer.close();
            } catch (IOException exception) {
                LumaMod.LOGGER.warn("Failed to close Lumi client load log", exception);
            } finally {
                this.writer = null;
            }
        }
    }

    static String sampleLine(ClientRuntimeLoadSnapshot snapshot) {
        ClientFrameStats frame = snapshot.frameStats() == null ? ClientFrameStats.empty() : snapshot.frameStats();
        ClientGpuMetrics gpu = snapshot.gpuMetrics() == null
                ? ClientGpuMetrics.unavailable("none", "not-sampled")
                : snapshot.gpuMetrics();
        return "time=" + quote(snapshot.time().toString())
                + " type=\"sample\""
                + " dimension=" + quote(snapshot.dimension())
                + " screen=" + quote(snapshot.screen())
                + " window=" + snapshot.windowWidth() + "x" + snapshot.windowHeight()
                + " gui=" + snapshot.guiWidth() + "x" + snapshot.guiHeight()
                + " frameSamples=" + frame.samples()
                + " fpsEstimate=" + number(frame.estimatedFps())
                + " frameAvgMs=" + number(frame.averageMillis())
                + " frameP95Ms=" + number(frame.p95Millis())
                + " frameMaxMs=" + number(frame.maxMillis())
                + " processCpuLoadPct=" + number(snapshot.processCpuLoadPercent())
                + " systemCpuLoadPct=" + number(snapshot.systemCpuLoadPercent())
                + " processCpuCores=" + number(snapshot.processCpuCores())
                + " processors=" + snapshot.availableProcessors()
                + " liveThreads=" + snapshot.liveThreads()
                + " heapUsedMiB=" + snapshot.heapUsedMiB()
                + " heapCommittedMiB=" + snapshot.heapCommittedMiB()
                + " heapMaxMiB=" + snapshot.heapMaxMiB()
                + " nonHeapUsedMiB=" + snapshot.nonHeapUsedMiB()
                + " directBufferUsedMiB=" + snapshot.directBufferUsedMiB()
                + " mappedBufferUsedMiB=" + snapshot.mappedBufferUsedMiB()
                + " gcCount=" + snapshot.gcCount()
                + " gcTimeMs=" + snapshot.gcTimeMillis()
                + " gpuProvider=" + quote(gpu.provider())
                + " gpuAvailable=" + gpu.available()
                + " gpuUtilPct=" + number(gpu.utilizationPercent())
                + " gpuMemoryUsedMiB=" + gpu.memoryUsedMiB()
                + " gpuMemoryTotalMiB=" + gpu.memoryTotalMiB()
                + " gpuDetail=" + quote(gpu.detail());
    }

    private void writeLine(String line) {
        synchronized (LOCK) {
            this.writeLineLocked(line);
        }
    }

    private void writeLineLocked(String line) {
        if (this.sinkFailed) {
            return;
        }
        try {
            BufferedWriter activeWriter = this.writer();
            activeWriter.write(line);
            activeWriter.newLine();
            activeWriter.flush();
        } catch (IOException exception) {
            this.sinkFailed = true;
            LumaMod.LOGGER.warn("Failed to write Lumi client load log", exception);
        }
    }

    private BufferedWriter writer() throws IOException {
        if (this.writer != null) {
            return this.writer;
        }
        Path path = this.configuredPath();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
        return this.writer;
    }

    private static String number(double value) {
        if (!Double.isFinite(value) || value < 0.0D) {
            return "na";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String quote(String value) {
        String normalized = value == null ? "" : value;
        return "\"" + normalized
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                + "\"";
    }
}
