package io.github.luma.minecraft.testing;

import java.util.function.BooleanSupplier;

/**
 * Opt-in gate for runtime test commands and tick work.
 */
public final class RuntimeTestingConfig {

    public static final String ENABLED_PROPERTY = "lumi.testing.enabled";
    public static final String ENABLED_ENVIRONMENT = "LUMI_TESTING_ENABLED";

    private final boolean enabled;

    private RuntimeTestingConfig(boolean enabled) {
        this.enabled = enabled;
    }

    public static RuntimeTestingConfig load() {
        return from(
                () -> Boolean.getBoolean(ENABLED_PROPERTY),
                () -> Boolean.parseBoolean(System.getenv(ENABLED_ENVIRONMENT))
        );
    }

    static RuntimeTestingConfig from(BooleanSupplier propertyEnabled, BooleanSupplier environmentEnabled) {
        boolean enabledByProperty = propertyEnabled != null && propertyEnabled.getAsBoolean();
        boolean enabledByEnvironment = environmentEnabled != null && environmentEnabled.getAsBoolean();
        return new RuntimeTestingConfig(enabledByProperty || enabledByEnvironment);
    }

    public boolean enabled() {
        return this.enabled;
    }
}
