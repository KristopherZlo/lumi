package io.github.luma.client.onboarding;

import io.github.luma.ui.onboarding.OnboardingTour;
import java.io.IOException;
import java.nio.file.Files;
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

    @Test
    void worldPromptUsesShortcutGlyphAndWrappedText() throws IOException {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/client/onboarding/ClientOnboardingFlowCoordinator.java"
        ));

        Assertions.assertTrue(source.contains("RoundedHudRenderer.key("));
        Assertions.assertTrue(source.contains("font.split("));
        Assertions.assertFalse(source.contains("drawString(font, this.tour.helpText()"));
    }

    private ClientOnboardingService service() {
        return new ClientOnboardingService(new ClientOnboardingStateRepository(this.tempDir.resolve("lumi-client.json")));
    }
}
