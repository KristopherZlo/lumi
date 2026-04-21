package io.github.luma.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectSettingsTest {

    @Test
    void sanitizeUsesDefaultsForNull() {
        ProjectSettings settings = ProjectSettings.sanitize(null);

        assertFalse(settings.autoVersionsEnabled());
        assertEquals(10, settings.autoVersionMinutes());
        assertEquals(5, settings.sessionIdleSeconds());
        assertEquals(10, settings.snapshotEveryVersions());
        assertEquals(0.20D, settings.snapshotVolumeThreshold());
        assertTrue(settings.safetySnapshotBeforeRestore());
        assertTrue(settings.previewGenerationEnabled());
        assertFalse(settings.debugLoggingEnabled());
    }

    @Test
    void sanitizeClampsInvalidNumbers() {
        ProjectSettings settings = ProjectSettings.sanitize(new ProjectSettings(
                true,
                0,
                -1,
                0,
                -3.0D,
                false,
                false,
                true
        ));

        assertTrue(settings.autoVersionsEnabled());
        assertEquals(10, settings.autoVersionMinutes());
        assertEquals(5, settings.sessionIdleSeconds());
        assertEquals(10, settings.snapshotEveryVersions());
        assertEquals(0.20D, settings.snapshotVolumeThreshold());
        assertFalse(settings.safetySnapshotBeforeRestore());
        assertFalse(settings.previewGenerationEnabled());
        assertTrue(settings.debugLoggingEnabled());
    }
}
