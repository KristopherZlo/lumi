package io.github.luma.telemetry;

import io.github.luma.LumaMod;
import io.github.luma.storage.GsonProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

public final class TelemetrySpoolRepository {

    private static final String FILE_NAME = "lumi-telemetry-spool.json";

    private final Path file;
    private final int capacity;

    public TelemetrySpoolRepository(int capacity) {
        this(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME), capacity);
    }

    public TelemetrySpoolRepository(Path file, int capacity) {
        this.file = file;
        this.capacity = Math.max(1, capacity);
    }

    public List<TelemetryEvent> load() {
        if (this.file == null || !Files.exists(this.file)) {
            return List.of();
        }
        try {
            Spool spool = GsonProvider.gson().fromJson(Files.readString(this.file), Spool.class);
            return this.trim(spool == null || spool.events == null ? List.of() : spool.events);
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Ignoring malformed Lumi telemetry spool at {}", this.file, exception);
            return List.of();
        }
    }

    public void save(List<TelemetryEvent> events) {
        if (this.file == null) {
            return;
        }
        try {
            Path parent = this.file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = this.file.resolveSibling(this.file.getFileName().toString() + ".tmp");
            Files.writeString(
                    temp,
                    GsonProvider.gson().toJson(new Spool(TelemetryEvent.SCHEMA_VERSION, this.trim(events))),
                    StandardCharsets.UTF_8
            );
            try {
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to save Lumi telemetry spool at {}", this.file, exception);
        }
    }

    public void clear() {
        this.save(List.of());
    }

    private List<TelemetryEvent> trim(List<TelemetryEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<TelemetryEvent> copy = new ArrayList<>(events);
        int from = Math.max(0, copy.size() - this.capacity);
        return List.copyOf(copy.subList(from, copy.size()));
    }

    private record Spool(
            int schemaVersion,
            List<TelemetryEvent> events
    ) {
    }
}
