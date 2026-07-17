package io.github.lumi.client.ui;

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
        assertTrue(source.contains("luma.settings.telemetry_enabled"));
        assertTrue(source.contains("panelWidth < 360"));
    }
}
