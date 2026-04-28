package io.github.luma.minecraft.testing;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Collects a durable report for one singleplayer runtime test run.
 */
final class SingleplayerTestLog {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final Instant startedAt = Instant.now();
    private final List<String> entries = new ArrayList<>();
    private int passedChecks;
    private int failedChecks;
    private Path writtenPath;

    void info(String message) {
        this.entries.add(this.line("INFO", message));
    }

    void pass(String phase, String check) {
        this.passedChecks += 1;
        this.entries.add(this.line("PASS", phase + " - " + check));
    }

    void fail(String phase, String check) {
        this.failedChecks += 1;
        this.entries.add(this.line("FAIL", phase + " - " + check));
    }

    void fail(String phase, String check, Throwable throwable) {
        this.fail(phase, check + ": " + this.message(throwable));
        this.entries.add(this.stackTrace(throwable));
    }

    int passedChecks() {
        return this.passedChecks;
    }

    int failedChecks() {
        return this.failedChecks;
    }

    int totalChecks() {
        return this.passedChecks + this.failedChecks;
    }

    boolean failed() {
        return this.failedChecks > 0;
    }

    Path writtenPath() {
        return this.writtenPath;
    }

    Path write(MinecraftServer server) throws java.io.IOException {
        if (this.writtenPath != null) {
            return this.writtenPath;
        }
        Path root = server.getWorldPath(LevelResource.ROOT)
                .resolve("lumi")
                .resolve("test-logs");
        Files.createDirectories(root);
        this.writtenPath = root.resolve("singleplayer-" + FILE_TIME.format(this.startedAt) + ".log");
        Files.writeString(this.writtenPath, this.render(), StandardCharsets.UTF_8);
        return this.writtenPath;
    }

    private String render() {
        List<String> lines = new ArrayList<>();
        lines.add("Lumi singleplayer runtime test log");
        lines.add("startedAt=" + this.startedAt);
        lines.add("finishedAt=" + Instant.now());
        lines.add("passedChecks=" + this.passedChecks);
        lines.add("failedChecks=" + this.failedChecks);
        lines.add("");
        lines.addAll(this.entries);
        lines.add("");
        return String.join(System.lineSeparator(), lines);
    }

    private String line(String level, String message) {
        return Instant.now() + " [" + level + "] " + message;
    }

    private String message(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private String stackTrace(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
