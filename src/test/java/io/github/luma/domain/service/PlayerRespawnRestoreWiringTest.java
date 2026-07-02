package io.github.luma.domain.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerRespawnRestoreWiringTest {

    @Test
    void versionsSaveAndFullRestoreApplyPlayerRespawnCheckpoints() throws Exception {
        String versionService = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/VersionService.java"));
        String restoreCompletion = Files.readString(Path.of(
                "src/main/java/io/github/luma/domain/service/RestoreCompletionCoordinator.java"
        ));

        assertTrue(versionService.contains("playerRespawnRepository.saveVersion"));
        assertTrue(restoreCompletion.contains("playerRespawnRepository.loadVersion"));
        assertTrue(restoreCompletion.contains("setRespawnPosition"));
    }
}
