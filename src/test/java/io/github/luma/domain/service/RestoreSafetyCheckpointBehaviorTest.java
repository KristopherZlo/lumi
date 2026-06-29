package io.github.luma.domain.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreSafetyCheckpointBehaviorTest {

    @Test
    void restoreSafetyCheckpointCreationHonorsProjectSetting() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/RestoreService.java"));

        assertTrue(source.contains("project.settings().safetySnapshotBeforeRestore()"));
        assertTrue(source.contains("shouldCreateSafetyCheckpoint"));
    }
}
