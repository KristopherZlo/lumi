package io.github.luma.debug;

import io.github.luma.LumaMod;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

final class StructuredDiagnosticsLog {

    private final String logName;
    private final String enabledFlag;
    private final String pathFlag;
    private final String defaultPath;
    private final boolean enabledByLoadLog;
    private final Object lock = new Object();
    private BufferedWriter writer;
    private boolean sinkFailed;

    StructuredDiagnosticsLog(String logName, String enabledFlag, String pathFlag, String defaultPath) {
        this(logName, enabledFlag, pathFlag, defaultPath, true);
    }

    StructuredDiagnosticsLog(
            String logName,
            String enabledFlag,
            String pathFlag,
            String defaultPath,
            boolean enabledByLoadLog
    ) {
        this.logName = logName == null || logName.isBlank() ? "diagnostic" : logName;
        this.enabledFlag = enabledFlag;
        this.pathFlag = pathFlag;
        this.defaultPath = defaultPath == null || defaultPath.isBlank() ? "logs/lumi-diagnostic.log" : defaultPath;
        this.enabledByLoadLog = enabledByLoadLog;
    }

    boolean enabled() {
        return Boolean.getBoolean(this.enabledFlag) || (this.enabledByLoadLog && LumaLoadLog.enabled());
    }

    Path configuredPath() {
        Path configured = Path.of(System.getProperty(this.pathFlag, this.defaultPath));
        return configured.isAbsolute() ? configured : Path.of("").toAbsolutePath().resolve(configured).normalize();
    }

    void event(String area, String name, String detail) {
        if (!this.enabled()) {
            return;
        }
        this.writeLine("time=" + quote(Instant.now().toString())
                + " type=\"event\""
                + " area=" + quote(normalize(area))
                + " name=" + quote(normalize(name))
                + " detail=" + quote(detail));
    }

    void span(String area, String name, long elapsedNanos, String detail) {
        if (!this.enabled()) {
            return;
        }
        this.writeLine("time=" + quote(Instant.now().toString())
                + " type=\"span\""
                + " area=" + quote(normalize(area))
                + " name=" + quote(normalize(name))
                + " elapsedMicros=" + (Math.max(0L, elapsedNanos) / 1_000L)
                + " elapsedMillis=" + (Math.max(0L, elapsedNanos) / 1_000_000L)
                + " thread=" + quote(Thread.currentThread().getName())
                + " detail=" + quote(detail));
    }

    void close() {
        synchronized (this.lock) {
            if (this.writer == null) {
                return;
            }
            this.writeLineLocked("time=" + quote(Instant.now().toString()) + " type=\"session-stop\"");
            this.closeWriter();
        }
    }

    private void writeLine(String line) {
        if (this.sinkFailed) {
            return;
        }
        synchronized (this.lock) {
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
            LumaMod.LOGGER.warn("Failed to write Lumi {} diagnostics log", this.logName, exception);
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
        this.writer.write("time=" + quote(Instant.now().toString())
                + " type=\"session-start\""
                + " log=" + quote(this.logName)
                + " enabledFlag=" + quote(this.enabledFlag)
                + " path=" + quote(path.toString()));
        this.writer.newLine();
        this.writer.flush();
        return this.writer;
    }

    private void closeWriter() {
        if (this.writer == null) {
            return;
        }
        try {
            this.writer.close();
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to close Lumi {} diagnostics log", this.logName, exception);
        } finally {
            this.writer = null;
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
}
