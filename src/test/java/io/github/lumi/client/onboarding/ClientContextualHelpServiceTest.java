package io.github.lumi.client.onboarding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientContextualHelpServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void dismissesAndResetsHintsIndependently() {
        ClientContextualHelpService service = new ClientContextualHelpService(
                new ClientOnboardingStateRepository(tempDir.resolve("onboarding")));

        assertTrue(service.shouldShowHint(ClientContextualHelpHint.MORE));
        service.dismissHint(ClientContextualHelpHint.MORE);
        assertFalse(service.shouldShowHint(ClientContextualHelpHint.MORE));

        service.resetHints();
        assertTrue(service.shouldShowHint(ClientContextualHelpHint.MORE));
    }
}
