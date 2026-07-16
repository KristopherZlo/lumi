package io.github.lumi.telemetry;

import io.github.lumi.LumiMod;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public final class TelemetrySettingsRepository {
    private final Path file;

    public TelemetrySettingsRepository() {
        this(FabricLoader.getInstance().getConfigDir().resolve("lumi-telemetry.properties"));
    }

    public TelemetrySettingsRepository(Path file) {
        this.file = file;
    }

    public TelemetrySettings load() {
        if (!Files.exists(file)) {
            return TelemetrySettings.defaults();
        }
        try {
            Properties properties = new Properties();
            properties.load(new StringReader(Files.readString(file)));
            String enabled = properties.getProperty("enabled");
            String noticeSeen = properties.getProperty("noticeSeen");
            if (!booleanValue(enabled) || !booleanValue(noticeSeen)) {
                return new TelemetrySettings(false, false);
            }
            return new TelemetrySettings(
                    Boolean.parseBoolean(enabled), Boolean.parseBoolean(noticeSeen));
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Could not read Lumi telemetry settings", failed);
            return new TelemetrySettings(false, false);
        }
    }

    public void save(TelemetrySettings settings) {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(settings.enabled()));
        properties.setProperty("noticeSeen", Boolean.toString(settings.noticeSeen()));
        try {
            StringWriter content = new StringWriter();
            properties.store(content, "Lumi diagnostic telemetry");
            replace(content.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Could not save Lumi telemetry settings", failed);
        }
    }

    private void replace(byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".lumi-telemetry-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean booleanValue(String value) {
        return "true".equals(value) || "false".equals(value);
    }
}
