package io.github.luma.client.onboarding;

import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientContextualHelpServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void workflowHintsStartVisible() {
        ClientContextualHelpService service = service(this.tempDir.resolve("lumi-client.json"));

        for (ClientContextualHelpHint hint : ClientContextualHelpHint.values()) {
            Assertions.assertTrue(service.shouldShowHint(hint), hint.id());
        }
    }

    @Test
    void dismissedHintIsHiddenUntilReset() {
        Path file = this.tempDir.resolve("lumi-client.json");
        ClientContextualHelpService service = service(file);

        service.dismissHint(ClientContextualHelpHint.PARTIAL_RESTORE);

        Assertions.assertFalse(service.shouldShowHint(ClientContextualHelpHint.PARTIAL_RESTORE));
        Assertions.assertTrue(service.shouldShowHint(ClientContextualHelpHint.RESTORE));

        service.resetHints();

        Assertions.assertTrue(service.shouldShowHint(ClientContextualHelpHint.PARTIAL_RESTORE));
    }

    private static ClientContextualHelpService service(Path file) {
        return new ClientContextualHelpService(new ClientOnboardingStateRepository(file));
    }
}
