package io.github.luma.client.onboarding;

import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientOnboardingServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void incompleteStateShowsOnboarding() {
        ClientOnboardingService service = service(this.tempDir.resolve("lumi-client.json"));

        Assertions.assertTrue(service.shouldShowOnboarding());
    }

    @Test
    void completedStateSuppressesOnboarding() {
        Path file = this.tempDir.resolve("lumi-client.json");
        ClientOnboardingStateRepository repository = new ClientOnboardingStateRepository(file);
        ClientOnboardingService service = new ClientOnboardingService(repository);

        service.markCompleted();

        Assertions.assertFalse(service.shouldShowOnboarding());
        Assertions.assertEquals(
                ClientOnboardingService.CURRENT_ONBOARDING_VERSION,
                repository.load().completedOnboardingVersion()
        );
    }

    @Test
    void lowerCompletedVersionShowsOnboardingAgain() throws Exception {
        Path file = this.tempDir.resolve("lumi-client.json");
        ClientOnboardingStateRepository repository = new ClientOnboardingStateRepository(file);
        repository.save(ClientOnboardingState.empty().withCompletedVersion(ClientOnboardingService.CURRENT_ONBOARDING_VERSION - 1));
        ClientOnboardingService service = new ClientOnboardingService(repository);

        Assertions.assertTrue(service.shouldShowOnboarding());
    }

    private static ClientOnboardingService service(Path file) {
        return new ClientOnboardingService(new ClientOnboardingStateRepository(file));
    }
}
