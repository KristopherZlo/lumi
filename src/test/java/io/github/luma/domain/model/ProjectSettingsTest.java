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
        assertFalse(settings.autoCheckpointEnabled());
        assertEquals(ProjectSettings.DEFAULT_AUTO_CHECKPOINT_LARGE_CHANGE_THRESHOLD, settings.autoCheckpointLargeChangeThreshold());
        assertTrue(settings.workspaceHudVisible());
        assertFalse(settings.hiddenCommitsVisible());
        assertFalse(settings.survivalModeAllowed());
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
                true,
                true,
                0,
                null
        ));

        assertTrue(settings.autoVersionsEnabled());
        assertEquals(10, settings.autoVersionMinutes());
        assertEquals(5, settings.sessionIdleSeconds());
        assertEquals(10, settings.snapshotEveryVersions());
        assertEquals(0.20D, settings.snapshotVolumeThreshold());
        assertFalse(settings.safetySnapshotBeforeRestore());
        assertFalse(settings.previewGenerationEnabled());
        assertTrue(settings.debugLoggingEnabled());
        assertTrue(settings.autoCheckpointEnabled());
        assertEquals(ProjectSettings.DEFAULT_AUTO_CHECKPOINT_LARGE_CHANGE_THRESHOLD, settings.autoCheckpointLargeChangeThreshold());
        assertTrue(settings.workspaceHudVisible());
        assertFalse(settings.hiddenCommitsVisible());
        assertFalse(settings.survivalModeAllowed());
    }

    @Test
    void sanitizePreservesWorkspaceHudVisibility() {
        ProjectSettings settings = ProjectSettings.sanitize(new ProjectSettings(
                false,
                10,
                5,
                10,
                0.20D,
                true,
                true,
                false,
                false,
                256,
                false
        ));

        assertEquals(256, settings.autoCheckpointLargeChangeThreshold());
        assertFalse(settings.workspaceHudVisible());
    }

    @Test
    void sanitizePreservesHiddenCommitVisibility() {
        ProjectSettings settings = ProjectSettings.sanitize(new ProjectSettings(
                false,
                10,
                5,
                10,
                0.20D,
                true,
                true,
                false,
                false,
                256,
                true,
                true
        ));

        assertTrue(settings.workspaceHudVisible());
        assertTrue(settings.hiddenCommitsVisible());
    }

    @Test
    void sanitizePreservesSurvivalModeAccess() {
        ProjectSettings settings = ProjectSettings.sanitize(new ProjectSettings(
                false,
                10,
                5,
                10,
                0.20D,
                true,
                true,
                false,
                false,
                256,
                true,
                false,
                true
        ));

        assertTrue(settings.survivalModeAllowed());
    }
}
