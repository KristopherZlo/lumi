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
    }

    @Test
    void savedCompletionRoundTrips() throws Exception {
        ClientOnboardingStateRepository repository = new ClientOnboardingStateRepository(this.tempDir.resolve("lumi-client.json"));

        repository.save(ClientOnboardingState.empty().withCompletedVersion(ClientOnboardingService.CURRENT_ONBOARDING_VERSION));

        Assertions.assertEquals(
                ClientOnboardingService.CURRENT_ONBOARDING_VERSION,
                repository.load().completedOnboardingVersion()
        );
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
