package io.github.lumi.client.onboarding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientOnboardingStateRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultsIncompleteAndPublishesCompletionAtomically() throws Exception {
        Path file = tempDir.resolve("client/onboarding");
        ClientOnboardingStateRepository repository =
                new ClientOnboardingStateRepository(file);

        assertFalse(repository.completed());
        repository.markCompleted();

        assertTrue(repository.completed());
        assertTrue(Files.readString(file).startsWith("1"));
        assertFalse(Files.exists(file.resolveSibling("onboarding.tmp")));
    }

    @Test
    void persistsDismissedHintsAndResetsOnlyHints() {
        Path file = tempDir.resolve("client/onboarding");
        ClientOnboardingStateRepository repository =
                new ClientOnboardingStateRepository(file);

        repository.markCompleted();
        repository.dismissHint("history");
        repository.dismissHint("branches");

        ClientOnboardingStateRepository reopened =
                new ClientOnboardingStateRepository(file);
        assertEquals(java.util.Set.of("history", "branches"),
                reopened.dismissedHintIds());

        reopened.resetHints();

        assertTrue(reopened.completed());
        assertTrue(reopened.dismissedHintIds().isEmpty());
    }
}
