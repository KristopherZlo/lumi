package io.github.luma.telemetry;

import io.github.luma.LumaMod;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

public interface TelemetryEnvironmentProvider {

    TelemetryEnvironment current();

    final class Static implements TelemetryEnvironmentProvider {

        private final TelemetryEnvironment environment;

        public Static(TelemetryEnvironment environment) {
            this.environment = environment;
        }

        @Override
        public TelemetryEnvironment current() {
            return this.environment;
        }
    }

    final class Fabric implements TelemetryEnvironmentProvider {

        @Override
        public TelemetryEnvironment current() {
            FabricLoader loader = FabricLoader.getInstance();
            String lumiVersion = loader.getModContainer(LumaMod.MOD_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
            String loaderVersion = loader.getModContainer("fabricloader")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
            List<TelemetryModInfo> mods = loader.getAllMods().stream()
                    .map(container -> new TelemetryModInfo(
                            container.getMetadata().getId(),
                            container.getMetadata().getVersion().getFriendlyString()
                    ))
                    .sorted(Comparator.comparing(TelemetryModInfo::id))
                    .toList();
            return new TelemetryEnvironment(
                    lumiVersion,
                    SharedConstants.getCurrentVersion().name(),
                    loaderVersion,
                    System.getProperty("java.version", "unknown"),
                    osFamily(System.getProperty("os.name", "unknown")),
                    System.getProperty("os.arch", "unknown"),
                    mods
            );
        }

        private static String osFamily(String osName) {
            String normalized = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("win")) {
                return "windows";
            }
            if (normalized.contains("mac")) {
                return "macos";
            }
            if (normalized.contains("linux")) {
                return "linux";
            }
            return "other";
        }
    }
}
