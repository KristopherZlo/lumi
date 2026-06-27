package io.github.luma.client.onboarding;

import io.github.luma.ui.onboarding.OnboardingTour;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientOnboardingFlowCoordinatorTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetCoordinator() {
        ClientOnboardingFlowCoordinator.getInstance().startBreakBlockStep("", "", "", this.service(), null);
    }

    @Test
    void undoRedoWorldPreviewSuppressesNormalShortcuts() {
        ClientOnboardingFlowCoordinator coordinator = ClientOnboardingFlowCoordinator.getInstance();

        coordinator.startWorldPreviewStep(
                "test-project",
                "main",
                "luma.status.project_ready",
                this.service(),
                new OnboardingTour(),
                OnboardingTour.Transition.EXECUTE_UNDO
        );

        Assertions.assertTrue(coordinator.suppressesLumiShortcuts());
    }

    @Test
    void nonPreviewStepDoesNotSuppressNormalShortcuts() {
        ClientOnboardingFlowCoordinator coordinator = ClientOnboardingFlowCoordinator.getInstance();

        coordinator.startBreakBlockStep(
                "test-project",
                "main",
                "luma.status.project_ready",
                this.service(),
                new OnboardingTour()
        );

        Assertions.assertFalse(coordinator.suppressesLumiShortcuts());
    }

    @Test
    void trackedWorldEditsCountsOnlyChangesAfterBaseline() {
        Assertions.assertEquals(0, ClientOnboardingFlowCoordinator.trackedWorldEdits(-1, 4));
        Assertions.assertEquals(0, ClientOnboardingFlowCoordinator.trackedWorldEdits(5, 3));
        Assertions.assertEquals(3, ClientOnboardingFlowCoordinator.trackedWorldEdits(5, 8));
    }

    private ClientOnboardingService service() {
        return new ClientOnboardingService(new ClientOnboardingStateRepository(this.tempDir.resolve("lumi-client.json")));
    }
}
