package io.github.luma.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryIntegrationGuardrailsTest {

    @Test
    void worldOperationTickPathReportsFailuresAndOutliersWithoutImportingSenderInfrastructure() throws IOException {
        Path source = Path.of("src/main/java/io/github/luma/minecraft/world/WorldOperationTickRunner.java");
        String text = Files.readString(source);

        assertTrue(text.contains("TelemetryService.getInstance().recordOperationFailed"),
                "WorldOperationTickRunner should report terminal failures to telemetry");
        assertTrue(text.contains("TelemetryService.getInstance().recordPerformanceOutlier"),
                "WorldOperationTickRunner should report severe outliers to telemetry");
        assertTrue(!text.contains("TelemetrySender") && !text.contains("TelemetrySpoolRepository"),
                "Tick path must not import sender/spool infrastructure");
    }

    @Test
    void shutdownHandlerReportsCancelledFailures() throws IOException {
        String text = Files.readString(Path.of("src/main/java/io/github/luma/minecraft/world/WorldOperationShutdownHandler.java"));

        assertTrue(text.contains("TelemetryService.getInstance().recordOperationFailed"),
                "Shutdown cancellation should be captured as a terminal operation failure");
    }

    @Test
    void rejectedUserActionsAreReportedFromControllers() throws IOException {
        String quickSave = Files.readString(Path.of("src/client/java/io/github/luma/ui/controller/QuickSaveScreenController.java"));
        String project = Files.readString(Path.of("src/client/java/io/github/luma/ui/controller/ProjectScreenController.java"));

        assertTrue(quickSave.contains("TelemetryService.getInstance().recordOperationRejected"),
                "Quick save rejections should be reported");
        assertTrue(project.contains("TelemetryService.getInstance().recordOperationRejected"),
                "Project screen rejections should be reported");
    }

    @Test
    void overlayFailuresAreReportedAndDisabled() throws IOException {
        String compare = Files.readString(Path.of("src/client/java/io/github/luma/ui/overlay/CompareOverlayRenderer.java"));
        String pending = Files.readString(Path.of("src/client/java/io/github/luma/ui/overlay/PendingChangesOverlayRenderer.java"));
        String recent = Files.readString(Path.of("src/client/java/io/github/luma/ui/overlay/RecentChangesOverlayRenderer.java"));

        assertTrue(compare.contains("TelemetryService.getInstance().recordRenderOverlayDisabled"),
                "Compare overlay failures should be reported");
        assertTrue(pending.contains("TelemetryService.getInstance().recordRenderOverlayDisabled"),
                "Pending overlay failures should be reported");
        assertTrue(recent.contains("TelemetryService.getInstance().recordRenderOverlayDisabled"),
                "Recent overlay failures should be reported");
    }

    @Test
    void settingsScreenExposesTelemetryControlsAndNoticeRuntimeBootstrapIsEnabled() throws IOException {
        String settings = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/SettingsScreen.java"));
        String client = Files.readString(Path.of("src/client/java/io/github/luma/LumaClient.java"));

        assertTrue(settings.contains("luma.settings.telemetry_title"),
                "Settings screen should expose telemetry controls");
        assertTrue(settings.contains("TelemetryService.getInstance().settings()"),
                "Settings screen should read telemetry state");
        assertTrue(client.contains("TelemetryService.getInstance().enableClientRuntime()"),
                "Client bootstrap should enable telemetry runtime only on the client");
        assertTrue(client.contains("TelemetryNoticeController"),
                "Client bootstrap should surface the first-run telemetry notice");
    }
}
