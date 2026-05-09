package io.github.luma.minecraft.bootstrap;

import io.github.luma.domain.model.WorldInitialBackupManifest;
import io.github.luma.domain.model.WorldOriginInfo;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.repository.WorldInitialBackupRepository;
import io.github.luma.storage.repository.WorldInstallationRepository;
import io.github.luma.storage.repository.WorldOriginRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldInitialBackupWarningServiceTest {

    @TempDir
    Path tempDir;

    private final WorldInitialBackupRepository backupRepository = new WorldInitialBackupRepository();
    private final WorldInstallationRepository installationRepository = new WorldInstallationRepository();
    private final WorldInitialBackupWarningService warningService = new WorldInitialBackupWarningService(
            this.backupRepository,
            this.installationRepository,
            new WorldOriginRepository()
    );

    @Test
    void warnsForExistingPreLumiWorldUntilBackupCompletes() throws Exception {
        this.createLevelDat();

        assertTrue(this.warningService.shouldWarnBeforeOpen(this.tempDir));

        this.warningService.acknowledgeWarning(this.tempDir);

        assertTrue(this.warningService.shouldWarnBeforeOpen(this.tempDir));
    }

    @Test
    void skipsFreshWorldBeforeLevelDataExists() throws Exception {
        assertFalse(this.warningService.shouldWarnBeforeOpen(this.tempDir));
    }

    @Test
    void skipsWorldCreatedWithLumiMarker() throws Exception {
        this.createLevelDat();
        this.warningService.markCreatedWithLumi(this.tempDir);

        assertFalse(this.warningService.shouldWarnBeforeOpen(this.tempDir));
    }

    @Test
    void skipsWorldCreatedWithLumiOriginFlag() throws Exception {
        this.createLevelDat();
        this.writeOriginInfo(true);

        assertFalse(this.warningService.shouldWarnBeforeOpen(this.tempDir));
    }

    @Test
    void skipsWorldWithCompletedBackupManifest() throws Exception {
        this.createLevelDat();
        this.backupRepository.save(this.tempDir, new WorldInitialBackupManifest(
                WorldInitialBackupManifest.CURRENT_SCHEMA_VERSION,
                "World",
                123L,
                "test",
                1024L,
                Map.of(),
                Instant.parse("2026-05-10T00:00:00Z"),
                Instant.parse("2026-05-10T00:00:01Z")
        ));

        assertFalse(this.warningService.shouldWarnBeforeOpen(this.tempDir));
    }

    private void createLevelDat() throws Exception {
        Files.write(this.tempDir.resolve("level.dat"), new byte[] {1});
    }

    private void writeOriginInfo(boolean createdWithLumi) throws Exception {
        Files.createDirectories(this.tempDir.resolve("lumi"));
        Files.writeString(this.tempDir.resolve("lumi").resolve("world-origin.json"), GsonProvider.gson().toJson(
                new WorldOriginInfo(
                        2,
                        "World",
                        "1.21.11",
                        4444,
                        123L,
                        createdWithLumi,
                        "datapacks",
                        Map.of(),
                        Instant.parse("2026-05-10T00:00:00Z"),
                        Instant.parse("2026-05-10T00:00:01Z")
                )
        ));
    }
}
