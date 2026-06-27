package io.github.luma.ui.screen;

import io.github.luma.client.onboarding.ClientOnboardingFlowCoordinator;
import io.github.luma.client.onboarding.ClientOnboardingService;
import io.github.luma.client.onboarding.ClientOnboardingStateRepository;
import io.github.luma.ui.onboarding.OnboardingTour;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

class OnboardingScreenTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetCoordinator() {
        ClientOnboardingFlowCoordinator.getInstance().startBreakBlockStep("", "", "", this.service(), null);
    }

    @Test
    void identifiesEscapeForOnboardingConsumption() {
        Assertions.assertTrue(OnboardingScreen.isEscapeKey(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)));
        Assertions.assertFalse(OnboardingScreen.isEscapeKey(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0)));
    }

    @Test
    void startsWorldStepCoordinatorFromStandaloneOnboarding() {
        OnboardingTour tour = new OnboardingTour();
        tour.next();
        OnboardingScreen.startWorldStepCoordinator(
                "test-project",
                "main",
                "luma.status.project_ready",
                this.service(),
                tour
        );

        Assertions.assertTrue(ClientOnboardingFlowCoordinator.getInstance().trackingWorldStep());
    }

    private ClientOnboardingService service() {
        return new ClientOnboardingService(new ClientOnboardingStateRepository(this.tempDir.resolve("lumi-client.json")));
    }
}
