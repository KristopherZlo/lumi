package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiSettingsScreenTest {
    @Test
    void exposesPersistedWorkspaceDefaultsAndLocalDiagnostics() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiSettingsScreen.java"));

        assertTrue(source.contains("ClientHistoryStore history"));
        assertTrue(source.contains("Consumer<WorkspaceSettings> updateWorkspace"));
        assertTrue(source.contains("WorkspaceView::active"));
        assertTrue(source.contains("!active.hideZoneCommits()"));
        assertTrue(source.contains("new WorkspaceSettings("));
        assertTrue(source.contains("!showZoneSaves, includeEntitiesOnRestore"));
        assertTrue(source.contains("luma.settings.show_hidden_commits"));
        assertTrue(source.contains("luma.settings.restore_entities"));
        assertTrue(source.contains("luma.settings.preview_generation"));
        assertTrue(source.contains("luma.settings.workspace_hud"));
        assertTrue(source.contains("luma.settings.automatic_versions"));
        assertTrue(source.contains("active.previewGenerationEnabled()"));
        assertTrue(source.contains("active.workspaceHudEnabled()"));
        assertTrue(source.contains("active.automaticVersionsEnabled()"));
        assertTrue(source.contains("toggleAutomaticVersions"));
        assertTrue(source.contains("luma.settings.telemetry_enabled"));
        assertTrue(source.contains("luma.settings.survival_mode"));
        assertTrue(source.contains("ClientSurvivalSettingsStore"));
        assertTrue(source.contains("requestSurvivalSettings.run()"));
        assertTrue(source.contains("updateSurvivalSettings.accept"));
        assertFalse(source.contains("renderCards("));
        assertTrue(source.contains("ClientContextualHelpHint.SETTINGS"));
        assertTrue(source.contains("contentOffset"));
        assertTrue(source.contains("public boolean mouseScrolled("));
    }

    @Test
    void settingsRowsStayInsideShortViewports() {
        assertEquals(6, LumiSettingsScreen.visibleSettingRows(220, 0));
        assertEquals(4, LumiSettingsScreen.visibleSettingRows(220, 48));
        assertEquals(8, LumiSettingsScreen.visibleSettingRows(340, 0));
    }
}
