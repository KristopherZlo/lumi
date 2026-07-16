package io.github.lumi.client.onboarding;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
