package io.github.luma.minecraft.testing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTestingConfigTest {

    @Test
    void disabledByDefault() {
        RuntimeTestingConfig config = RuntimeTestingConfig.from(() -> false, () -> false);

        assertFalse(config.enabled());
    }

    @Test
    void enabledByJvmProperty() {
        RuntimeTestingConfig config = RuntimeTestingConfig.from(() -> true, () -> false);

        assertTrue(config.enabled());
    }

    @Test
    void enabledByEnvironmentVariable() {
        RuntimeTestingConfig config = RuntimeTestingConfig.from(() -> false, () -> true);

        assertTrue(config.enabled());
    }
}
