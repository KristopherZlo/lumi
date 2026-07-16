package io.github.lumi.telemetry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.lumi.LumiMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

public final class TelemetrySpoolRepository {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Path file;
    private final int capacity;

    public TelemetrySpoolRepository(int capacity) {
        this(FabricLoader.getInstance().getConfigDir().resolve("lumi-telemetry-spool.json"),
                capacity);
    }

    public TelemetrySpoolRepository(Path file, int capacity) {
        this.file = file;
        this.capacity = Math.max(1, capacity);
    }

    public List<TelemetryEvent> load() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            TelemetryEvent[] events = GSON.fromJson(Files.readString(file), TelemetryEvent[].class);
            return trim(events == null ? List.of() : Arrays.asList(events));
        } catch (Exception failed) {
            LumiMod.LOGGER.warn("Ignoring malformed Lumi telemetry spool", failed);
            return List.of();
        }
    }

    public void save(List<TelemetryEvent> events) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".lumi-spool-", ".tmp");
            try {
                Files.writeString(temporary, GSON.toJson(trim(events)),
                        StandardCharsets.UTF_8);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Could not save Lumi telemetry spool", failed);
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Could not clear Lumi telemetry spool", failed);
        }
    }

    private List<TelemetryEvent> trim(List<TelemetryEvent> events) {
        List<TelemetryEvent> safe = events == null
                ? List.of() : events.stream().filter(java.util.Objects::nonNull).toList();
        return List.copyOf(safe.subList(Math.max(0, safe.size() - capacity), safe.size()));
    }
}
