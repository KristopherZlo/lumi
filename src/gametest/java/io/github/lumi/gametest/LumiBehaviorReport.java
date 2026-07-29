package io.github.lumi.gametest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes one self-contained diagnostic report for the client behavior test. */
final class LumiBehaviorReport implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("lumi-behavior-test");
    private static final DateTimeFormatter RUN_NAME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Path directory;
    private final Path snapshots;
    private final Path runtimeLog;
    private final long runtimeLogStart;
    private final BufferedWriter events;
    private final String scenario;
    private int snapshotNumber;

    private LumiBehaviorReport(
            Path directory,
            Path runtimeLog,
            long runtimeLogStart,
            BufferedWriter events,
            String scenario) throws IOException {
        this.directory = directory;
        snapshots = Files.createDirectories(directory.resolve("snapshots"));
        this.runtimeLog = runtimeLog;
        this.runtimeLogStart = runtimeLogStart;
        this.events = events;
        this.scenario = scenario;
        event("test", scenario, "started", 0, 0, "");
        LOGGER.info("Lumi behavior report: {}", directory.toAbsolutePath());
    }

    static LumiBehaviorReport create(Path gameDirectory, String scenario)
            throws IOException {
        Path buildDirectory = gameDirectory.toAbsolutePath().normalize()
                .getParent().getParent();
        Path directory = Files.createDirectories(buildDirectory
                .resolve("reports/lumi-behavior")
                .resolve(RUN_NAME.format(Instant.now()) + "-" + fileName(scenario)));
        BufferedWriter events = Files.newBufferedWriter(
                directory.resolve("events.jsonl"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Path runtimeLog = gameDirectory.resolve("logs/latest.log");
        long runtimeLogStart = Files.exists(runtimeLog) ? Files.size(runtimeLog) : 0;
        return new LumiBehaviorReport(
                directory, runtimeLog, runtimeLogStart, events, scenario);
    }

    Path directory() {
        return directory;
    }

    void event(
            String type,
            String name,
            String status,
            int ticks,
            long millis,
            String detail) {
        String line = "{"
                + field("time", Instant.now().toString()) + ","
                + field("type", type) + ","
                + field("name", name) + ","
                + field("status", status) + ","
                + "\"ticks\":" + ticks + ","
                + "\"millis\":" + millis + ","
                + field("detail", detail)
                + "}";
        writeLine(line);
        LOGGER.info("Behavior {} {}: {} ({} ticks, {} ms){}",
                type, name, status, ticks, millis,
                detail.isBlank() ? "" : " - " + detail);
    }

    void snapshot(
            String name,
            String digest,
            int sections,
            int entities,
            long captureMillis) {
        int number = ++snapshotNumber;
        String body = "{"
                + field("name", name) + ","
                + field("sha256", digest) + ","
                + "\"sections\":" + sections + ","
                + "\"entities\":" + entities + ","
                + "\"captureMillis\":" + captureMillis
                + "}\n";
        try {
            Files.writeString(
                    snapshots.resolve(String.format(
                            Locale.ROOT, "%02d-%s.json", number, fileName(name))),
                    body, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot write behavior snapshot", failed);
        }
        event("snapshot", name, "captured", 0, captureMillis,
                digest + " sections=" + sections + " entities=" + entities);
    }

    void assertNoRuntimeFailures() {
        try {
            long offset = Files.size(runtimeLog) < runtimeLogStart
                    ? 0 : runtimeLogStart;
            String appended;
            try (var input = Files.newInputStream(runtimeLog)) {
                input.skipNBytes(offset);
                appended = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            var failures = appended.lines()
                    .filter(line -> line.contains("/ERROR] (lumi)")
                            || line.contains("Uncaught exception in thread \"Lumi-")
                            || line.contains("lost connection: Internal Exception")
                            || line.contains("UUID of added entity already exists")
                            || line.contains("at knot//io.github.lumi"))
                    .limit(8)
                    .toList();
            if (!failures.isEmpty()) {
                event("gate", "runtime_health", "failed", 0, 0,
                        String.join(" | ", failures));
                throw new AssertionError(
                        "Lumi runtime failures: " + String.join(" | ", failures));
            }
            event("gate", "runtime_health", "succeeded", 0, 0, "");
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot inspect Lumi runtime log", failed);
        }
    }

    private synchronized void writeLine(String line) {
        try {
            events.write(line);
            events.newLine();
            events.flush();
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot write behavior event", failed);
        }
    }

    private static String field(String name, String value) {
        return "\"" + name + "\":\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(
                                Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String fileName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
    }

    @Override
    public void close() throws IOException {
        event("test", scenario, "finished", 0, 0, "");
        events.close();
        try {
            Files.copy(runtimeLog, directory.resolve("latest.log"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failed) {
            LOGGER.warn("Could not preserve client behavior log", failed);
        }
    }
}
