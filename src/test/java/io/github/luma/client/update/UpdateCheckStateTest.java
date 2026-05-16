package io.github.luma.client.update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckStateTest {

    @Test
    void staleWhenNeverCheckedOrIntervalElapsed() {
        Instant now = Instant.parse("2026-05-16T10:00:00Z");

        assertTrue(UpdateCheckState.empty().shouldCheck(now, Duration.ofHours(12)));
        assertFalse(new UpdateCheckState(
                now.minus(Duration.ofHours(2)),
                null,
                ""
        ).shouldCheck(now, Duration.ofHours(12)));
        assertTrue(new UpdateCheckState(
                now.minus(Duration.ofHours(13)),
                null,
                ""
        ).shouldCheck(now, Duration.ofHours(12)));
    }

    @Test
    void dismissedVersionDoesNotPromptAgain() {
        UpdateRelease release = release("0.1.0-alpha.2");
        UpdateCheckState state = UpdateCheckState.empty()
                .withChecked(Instant.parse("2026-05-16T10:00:00Z"), UpdateCheckResult.available(release))
                .withDismissedVersion("0.1.0-alpha.2");

        assertFalse(state.promptRelease().isPresent());
        assertTrue(state.withChecked(
                Instant.parse("2026-05-16T11:00:00Z"),
                UpdateCheckResult.available(release("0.1.0-alpha.3"))
        ).promptRelease().isPresent());
    }

    private static UpdateRelease release(String version) {
        return new UpdateRelease(
                version,
                100002,
                List.of("1.21.11"),
                "fabric",
                "stable",
                "Lumi " + version,
                "Summary",
                "https://example.com/download",
                "https://example.com/changelog",
                ""
        );
    }
}
