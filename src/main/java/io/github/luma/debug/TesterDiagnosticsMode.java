package io.github.luma.debug;

import io.github.luma.LumaMod;
import java.util.Locale;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Enables bounded diagnostics for tester builds without turning on global debug
 * logging.
 */
public final class TesterDiagnosticsMode {

    private static final String ENABLED_FLAG = "lumi.testerDiagnostics";
    private static final String TESTER_VERSION_MARKER = "tester";

    private final Supplier<String> modVersion;

    public TesterDiagnosticsMode() {
        this(TesterDiagnosticsMode::currentModVersion);
    }

    TesterDiagnosticsMode(Supplier<String> modVersion) {
        this.modVersion = modVersion;
    }

    public static boolean applyDefaults() {
        return new TesterDiagnosticsMode().apply();
    }

    boolean apply() {
        if (!this.enabled()) {
            return false;
        }

        setDefault("lumi.loadLog", "true");
        setDefault("lumi.clientLoadLog", "true");
        setDefault("lumi.lightLog", "true");
        setDefault("lumi.blockApplyLog", "true");
        setDefault("lumi.partialRestoreLog", "true");
        setDefault("lumi.loadLog.slowMs", "25");
        setDefault("lumi.loadLog.summarySeconds", "30");
        setDefault("lumi.loadLog.top", "20");
        setDefault("lumi.clientLoadLog.sampleTicks", "20");
        setDefault("lumi.clientLoadLog.gpuSampleSeconds", "10");
        return true;
    }

    boolean enabled() {
        return Boolean.getBoolean(ENABLED_FLAG) || testerVersion(this.modVersion.get());
    }

    static boolean testerVersion(String version) {
        return version != null
                && version.toLowerCase(Locale.ROOT).contains(TESTER_VERSION_MARKER);
    }

    private static void setDefault(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static String currentModVersion() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(LumaMod.MOD_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("");
        } catch (RuntimeException exception) {
            return "";
        }
    }
}
