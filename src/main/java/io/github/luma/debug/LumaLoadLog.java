package io.github.luma.debug;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.OperationHandle;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Separate runtime-load diagnostics for finding expensive Lumi code paths.
 */
public final class LumaLoadLog {

    private static final String ENABLED_FLAG = "lumi.loadLog";
    private static final String PATH_FLAG = "lumi.loadLog.path";
    private static final String SLOW_MILLIS_FLAG = "lumi.loadLog.slowMs";
    private static final String SUMMARY_SECONDS_FLAG = "lumi.loadLog.summarySeconds";
    private static final String TOP_LIMIT_FLAG = "lumi.loadLog.top";
    private static final boolean ENABLED = Boolean.getBoolean(ENABLED_FLAG);
    private static final long SLOW_NANOS = Duration.ofMillis(Math.max(0L, Long.getLong(SLOW_MILLIS_FLAG, 10L))).toNanos();
    private static final long SUMMARY_INTERVAL_NANOS = Duration.ofSeconds(Math.max(1L, Long.getLong(SUMMARY_SECONDS_FLAG, 30L))).toNanos();
    private static final int TOP_LIMIT = Math.max(1, Integer.getInteger(TOP_LIMIT_FLAG, 12));
    private static final LoadLogSummary SUMMARY = new LoadLogSummary();
    private static final Object LOCK = new Object();
    private static final TimedSection NO_OP_SECTION = new NoOpTimedSection();

    private static BufferedWriter writer;
    private static boolean sinkFailed;
    private static long lastSummaryNanos = System.nanoTime();

    private LumaLoadLog() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static Path configuredPath() {
        Path configured = Path.of(System.getProperty(PATH_FLAG, "logs/lumi-load.log"));
        return configured.isAbsolute() ? configured : Path.of("").toAbsolutePath().resolve(configured).normalize();
    }

    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static TimedSection measure(String area, String name) {
        return measure(area, name, "");
    }

    public static TimedSection measure(String area, String name, String detail) {
        if (!ENABLED) {
            return NO_OP_SECTION;
        }
        return new ActiveTimedSection(area, name, detail, System.nanoTime());
    }

    public static void recordSince(String area, String name, long startedAtNanos) {
        recordSince(area, name, startedAtNanos, "");
    }

    public static void recordSince(String area, String name, long startedAtNanos, String detail) {
        if (!ENABLED || startedAtNanos <= 0L) {
            return;
        }
        record(area, name, System.nanoTime() - startedAtNanos, detail);
    }

    public static void record(String area, String name, long elapsedNanos) {
        record(area, name, elapsedNanos, "");
    }

    public static void record(String area, String name, long elapsedNanos, String detail) {
        if (!ENABLED || elapsedNanos <= 0L) {
            return;
        }

        synchronized (LOCK) {
            SUMMARY.record(area, name, elapsedNanos);
            if (elapsedNanos >= SLOW_NANOS) {
                writeLine(spanLine("span", area, name, elapsedNanos, detail));
            }
            writeSummaryIfDue(false);
        }
    }

    public static void operationMetrics(OperationHandle handle, String metricsSummary) {
        if (!ENABLED || handle == null || metricsSummary == null || metricsSummary.isBlank()) {
            return;
        }

        synchronized (LOCK) {
            writeLine("time=" + quote(Instant.now().toString())
                    + " type=\"operation-metrics\""
                    + " label=" + quote(handle.label())
                    + " projectId=" + quote(handle.projectId())
                    + " operationId=" + quote(handle.id())
                    + " metrics=" + quote(metricsSummary));
            writeSummaryIfDue(false);
        }
    }

    public static void event(String area, String name, String detail) {
        if (!ENABLED) {
            return;
        }

        synchronized (LOCK) {
            writeLine("time=" + quote(Instant.now().toString())
                    + " type=\"event\""
                    + " area=" + quote(normalize(area))
                    + " name=" + quote(normalize(name))
                    + " detail=" + quote(detail));
            writeSummaryIfDue(false);
        }
    }

    public static void close() {
        if (!ENABLED) {
            return;
        }

        synchronized (LOCK) {
            writeSummaryIfDue(true);
            writeLine("time=" + quote(Instant.now().toString()) + " type=\"session-stop\"");
            closeWriter();
        }
    }

    private static String spanLine(String type, String area, String name, long elapsedNanos, String detail) {
        return "time=" + quote(Instant.now().toString())
                + " type=" + quote(type)
                + " area=" + quote(normalize(area))
                + " name=" + quote(normalize(name))
                + " elapsedMicros=" + (elapsedNanos / 1_000L)
                + " elapsedMillis=" + (elapsedNanos / 1_000_000L)
                + " thread=" + quote(Thread.currentThread().getName())
                + " detail=" + quote(detail);
    }

    private static void writeSummaryIfDue(boolean force) {
        if (SUMMARY.empty()) {
            return;
        }

        long now = System.nanoTime();
        if (!force && now - lastSummaryNanos < SUMMARY_INTERVAL_NANOS) {
            return;
        }

        lastSummaryNanos = now;
        List<LoadLogSummary.LoadLogEntrySnapshot> top = SUMMARY.topByTotal(TOP_LIMIT);
        for (int index = 0; index < top.size(); index++) {
            LoadLogSummary.LoadLogEntrySnapshot entry = top.get(index);
            writeLine("time=" + quote(Instant.now().toString())
                    + " type=\"summary\""
                    + " rank=" + (index + 1)
                    + " area=" + quote(entry.area())
                    + " name=" + quote(entry.name())
                    + " count=" + entry.count()
                    + " totalMillis=" + (entry.totalNanos() / 1_000_000L)
                    + " averageMicros=" + (entry.averageNanos() / 1_000L)
                    + " maxMillis=" + (entry.maxNanos() / 1_000_000L));
        }
    }

    private static void writeLine(String line) {
        if (sinkFailed) {
            return;
        }

        try {
            BufferedWriter activeWriter = writer();
            activeWriter.write(line);
            activeWriter.newLine();
            activeWriter.flush();
        } catch (IOException exception) {
            sinkFailed = true;
            LumaMod.LOGGER.warn("Failed to write Lumi load log", exception);
        }
    }

    private static BufferedWriter writer() throws IOException {
        if (writer != null) {
            return writer;
        }

        Path path = configuredPath();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
        writer.write("time=" + quote(Instant.now().toString())
                + " type=\"session-start\""
                + " slowMillis=" + (SLOW_NANOS / 1_000_000L)
                + " summarySeconds=" + (SUMMARY_INTERVAL_NANOS / 1_000_000_000L)
                + " topLimit=" + TOP_LIMIT);
        writer.newLine();
        writer.flush();
        return writer;
    }

    private static void closeWriter() {
        if (writer == null) {
            return;
        }

        try {
            writer.close();
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to close Lumi load log", exception);
        } finally {
            writer = null;
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
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

    public abstract static class TimedSection implements AutoCloseable {

        @Override
        public abstract void close();
    }

    private static final class NoOpTimedSection extends TimedSection {

        @Override
        public void close() {
        }
    }

    private static final class ActiveTimedSection extends TimedSection {

        private final String area;
        private final String name;
        private final String detail;
        private final long startedAtNanos;
        private boolean closed;

        private ActiveTimedSection(String area, String name, String detail, long startedAtNanos) {
            this.area = area;
            this.name = name;
            this.detail = detail;
            this.startedAtNanos = startedAtNanos;
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            LumaLoadLog.recordSince(this.area, this.name, this.startedAtNanos, this.detail);
        }
    }
}
