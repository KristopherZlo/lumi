package io.github.luma.minecraft.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleplayerSmokeSourceTest {

    @Test
    void singleplayerRunnerIncludesWorkZoneSmoke() throws IOException {
        String source = Files.readString(Path.of("src/gametest/java/io/github/luma/minecraft/testing/SingleplayerTestRun.java"));

        assertTrue(source.contains("START_WORK_ZONE_SMOKE"), "Singleplayer smoke should start a work-zone phase");
        assertTrue(source.contains("CHECK_WORK_ZONE_SMOKE"), "Singleplayer smoke should verify work-zone save output");
        assertTrue(source.contains("new WorkZoneService()"), "Singleplayer smoke should exercise WorkZoneService");
        assertTrue(source.contains("ProjectVersionVisibility.WORK_ZONE_ID_METADATA"),
                "Singleplayer smoke should verify zone metadata on saved versions");
        assertTrue(source.contains("loadWorkZoneVersions"),
                "Singleplayer smoke should inspect the zone-history query");
    }

    @Test
    void largeHistoryDiagnosticsReportStorageBytes() throws IOException {
        String source = Files.readString(Path.of("src/gametest/java/io/github/luma/minecraft/testing/SingleplayerLargeHistoryScenario.java"));

        assertTrue(source.contains("storageBytesByCheckpoint"),
                "Large history diagnostics should retain byte measurements per heavy checkpoint");
        assertTrue(source.contains("measureProjectStorageBytes"),
                "Large history diagnostics should measure project storage on disk");
        assertTrue(source.contains("storageBytes="),
                "Large history diagnostics summary should print storage byte measurements");
    }
}
