package io.github.luma.telemetry;

import io.github.luma.LumaMod;
import io.github.luma.storage.GsonProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;

public final class TelemetrySettingsRepository {

    private static final String FILE_NAME = "lumi-telemetry.json";

    private final Path file;
    private final String defaultEndpointUrl;
    private final Supplier<String> installationIds;

    public TelemetrySettingsRepository(String defaultEndpointUrl, Supplier<String> installationIds) {
        this(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME), defaultEndpointUrl, installationIds);
    }

    public TelemetrySettingsRepository(Path file, String defaultEndpointUrl, Supplier<String> installationIds) {
        this.file = file;
        this.defaultEndpointUrl = defaultEndpointUrl;
        this.installationIds = installationIds;
    }

    public TelemetrySettings load() {
        if (this.file == null || !Files.exists(this.file)) {
            return TelemetrySettings.defaults(this.defaultEndpointUrl, this.installationIds);
        }
        try {
            TelemetrySettings settings = GsonProvider.gson().fromJson(Files.readString(this.file), TelemetrySettings.class);
            return settings == null
                    ? TelemetrySettings.defaults(this.defaultEndpointUrl, this.installationIds)
                    : settings.normalized(this.defaultEndpointUrl, this.installationIds);
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Ignoring malformed Lumi telemetry settings at {}", this.file, exception);
            return TelemetrySettings.defaults(this.defaultEndpointUrl, this.installationIds);
        }
    }

    public void save(TelemetrySettings settings) {
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
                    GsonProvider.gson().toJson(settings == null
                            ? TelemetrySettings.defaults(this.defaultEndpointUrl, this.installationIds)
                            : settings.normalized(this.defaultEndpointUrl, this.installationIds)),
                    StandardCharsets.UTF_8
            );
            try {
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to save Lumi telemetry settings at {}", this.file, exception);
        }
    }
}
