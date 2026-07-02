package io.github.luma.storage.repository;

import io.github.luma.domain.model.PlayerRespawnPoint;
import io.github.luma.storage.ProjectLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerRespawnRepositoryTest {

    @Test
    void versionRespawnsRoundTrip(@TempDir Path tempDir) throws Exception {
        PlayerRespawnRepository repository = new PlayerRespawnRepository();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        PlayerRespawnPoint point = new PlayerRespawnPoint(
                "00000000-0000-0000-0000-000000000001",
                "Steve",
                "minecraft:overworld",
                4,
                65,
                -2,
                90.0F,
                0.0F,
                true
        );

        repository.saveVersion(layout, "v0002", List.of(point));

        assertEquals(List.of(point), repository.loadVersion(layout, "v0002"));
        assertTrue(Files.readString(layout.playerRespawnsFile()).contains("v0002"));
    }

    @Test
    void missingRespawnFileLoadsEmpty(@TempDir Path tempDir) throws Exception {
        PlayerRespawnRepository repository = new PlayerRespawnRepository();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));

        assertEquals(List.of(), repository.loadVersion(layout, "v0001"));
    }
}
