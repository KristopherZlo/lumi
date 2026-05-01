package io.github.luma.client.onboarding;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientOnboardingStateRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void missingFileMeansOnboardingIncomplete() {
        ClientOnboardingStateRepository repository = new ClientOnboardingStateRepository(this.tempDir.resolve("lumi-client.json"));

        ClientOnboardingState state = repository.load();

        Assertions.assertEquals(ClientOnboardingState.CURRENT_SCHEMA_VERSION, state.schemaVersion());
        Assertions.assertEquals(0, state.completedOnboardingVersion());
        Assertions.assertTrue(state.dismissedContextualHintIds().isEmpty());
    }

    @Test
    void savedCompletionRoundTrips() throws Exception {
        ClientOnboardingStateRepository repository = new ClientOnboardingStateRepository(this.tempDir.resolve("lumi-client.json"));

        repository.save(ClientOnboardingState.empty()
                .withCompletedVersion(ClientOnboardingService.CURRENT_ONBOARDING_VERSION)
                .withDismissedContextualHint(ClientContextualHelpHint.HISTORY.id()));

        Assertions.assertEquals(
                ClientOnboardingService.CURRENT_ONBOARDING_VERSION,
                repository.load().completedOnboardingVersion()
        );
        Assertions.assertTrue(repository.load().dismissedContextualHintIds().contains(ClientContextualHelpHint.HISTORY.id()));
    }

    @Test
    void v1StateNormalizesToV2WithNoDismissedHints() throws Exception {
        Path file = this.tempDir.resolve("lumi-client.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"completedOnboardingVersion\":1}");
        ClientOnboardingStateRepository repository = new ClientOnboardingStateRepository(file);

        ClientOnboardingState state = repository.load();

        Assertions.assertEquals(ClientOnboardingState.CURRENT_SCHEMA_VERSION, state.schemaVersion());
        Assertions.assertEquals(1, state.completedOnboardingVersion());
        Assertions.assertTrue(state.dismissedContextualHintIds().isEmpty());
    }

    @Test
    void malformedFileFallsBackToIncompleteState() throws Exception {
        Path file = this.tempDir.resolve("lumi-client.json");
        Files.writeString(file, "{not-json");
        ClientOnboardingStateRepository repository = new ClientOnboardingStateRepository(file);

        ClientOnboardingState state = repository.load();

        Assertions.assertEquals(0, state.completedOnboardingVersion());
    }
}
