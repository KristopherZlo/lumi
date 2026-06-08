package io.github.luma.telemetry;

import java.util.List;

public record TelemetryEnvironment(
        String lumiVersion,
        String minecraftVersion,
        String fabricLoaderVersion,
        String javaVersion,
        String osFamily,
        String osArch,
        List<TelemetryModInfo> mods
) {

    public TelemetryEnvironment {
        mods = mods == null ? List.of() : List.copyOf(mods);
    }
}
