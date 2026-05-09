package io.github.luma.storage.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Stores small world-level installation markers outside project history.
 */
public final class WorldInstallationRepository {

    private static final String CREATED_WITH_LUMI_MARKER = "created-with-lumi.marker";
    private static final String BACKUP_WARNING_ACKNOWLEDGEMENT = "alpha-backup-warning-acknowledged.txt";

    public boolean createdWithLumi(Path worldRoot) {
        return Files.exists(this.createdWithLumiMarker(worldRoot));
    }

    public void markCreatedWithLumi(Path worldRoot) throws IOException {
        this.writeMarker(
                this.createdWithLumiMarker(worldRoot),
                "Created through Lumi at " + Instant.now()
        );
    }

    public boolean backupWarningAcknowledged(Path worldRoot) {
        return Files.exists(this.backupWarningAcknowledgement(worldRoot));
    }

    public void acknowledgeBackupWarning(Path worldRoot) throws IOException {
        this.writeMarker(
                this.backupWarningAcknowledgement(worldRoot),
                "Acknowledged Lumi alpha backup warning at " + Instant.now()
        );
    }

    private Path createdWithLumiMarker(Path worldRoot) {
        return this.lumiRoot(worldRoot).resolve(CREATED_WITH_LUMI_MARKER);
    }

    private Path backupWarningAcknowledgement(Path worldRoot) {
        return this.lumiRoot(worldRoot)
                .resolve("pre-mod-backup")
                .resolve(BACKUP_WARNING_ACKNOWLEDGEMENT);
    }

    private Path lumiRoot(Path worldRoot) {
        return worldRoot.resolve("lumi");
    }

    private void writeMarker(Path file, String content) throws IOException {
        StorageIo.writeAtomically(file, output -> output.write(content.getBytes(StandardCharsets.UTF_8)));
    }
}
