package io.github.luma.client.update;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdateCheckStateRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsCachedPromptState() throws Exception {
        Path file = this.tempDir.resolve("update-check.json");
        UpdateCheckStateRepository repository = new UpdateCheckStateRepository(file);
        UpdateCheckState state = UpdateCheckState.empty()
                .withChecked(Instant.parse("2026-05-16T10:00:00Z"), UpdateCheckResult.available(release()))
                .withDismissedVersion("0.1.0-alpha.1");

        repository.save(state);
        UpdateCheckState loaded = repository.load();

        assertEquals(Instant.parse("2026-05-16T10:00:00Z"), loaded.lastCheckedAt());
        assertEquals("0.1.0-alpha.2", loaded.availableRelease().version());
        assertEquals("0.1.0-alpha.1", loaded.dismissedVersion());
    }

    private static UpdateRelease release() {
        return new UpdateRelease(
                "0.1.0-alpha.2",
                100002,
                List.of("1.21.11"),
                "fabric",
                "stable",
                "Lumi 0.1.0 alpha 2",
                "Summary",
                "https://example.com/download",
                "https://example.com/changelog",
                ""
        );
    }
}
