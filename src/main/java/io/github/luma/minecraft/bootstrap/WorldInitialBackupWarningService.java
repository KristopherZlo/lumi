package io.github.luma.minecraft.bootstrap;

import io.github.luma.domain.model.WorldOriginInfo;
import io.github.luma.storage.repository.WorldInitialBackupRepository;
import io.github.luma.storage.repository.WorldInstallationRepository;
import io.github.luma.storage.repository.WorldOriginRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides whether a pre-Lumi world needs the one-time alpha backup warning.
 */
public final class WorldInitialBackupWarningService {

    private final WorldInitialBackupRepository backupRepository;
    private final WorldInstallationRepository installationRepository;
    private final WorldOriginRepository originRepository;

    public WorldInitialBackupWarningService() {
        this(new WorldInitialBackupRepository(), new WorldInstallationRepository(), new WorldOriginRepository());
    }

    WorldInitialBackupWarningService(
            WorldInitialBackupRepository backupRepository,
            WorldInstallationRepository installationRepository,
            WorldOriginRepository originRepository
    ) {
        this.backupRepository = backupRepository;
        this.installationRepository = installationRepository;
        this.originRepository = originRepository;
    }

    public boolean shouldWarnBeforeOpen(Path worldRoot) throws IOException {
        if (worldRoot == null || !Files.exists(worldRoot.resolve("level.dat"))) {
            return false;
        }
        if (this.createdWithLumi(worldRoot)) {
            return false;
        }
        if (this.backupRepository.hasCompletedBackup(worldRoot)) {
            return false;
        }
        return !this.installationRepository.backupWarningAcknowledged(worldRoot);
    }

    public void acknowledgeWarning(Path worldRoot) throws IOException {
        if (worldRoot != null) {
            this.installationRepository.acknowledgeBackupWarning(worldRoot);
        }
    }

    public void markCreatedWithLumi(Path worldRoot) throws IOException {
        if (worldRoot != null) {
            this.installationRepository.markCreatedWithLumi(worldRoot);
        }
    }

    private boolean createdWithLumi(Path worldRoot) throws IOException {
        if (this.installationRepository.createdWithLumi(worldRoot)) {
            return true;
        }
        return this.originRepository.load(worldRoot)
                .map(WorldOriginInfo::createdWithLumi)
                .orElse(false);
    }
}
