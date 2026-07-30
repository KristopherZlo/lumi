package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientDeveloperModeTest {
    @TempDir Path tempDir;

    @Test
    void persistsAnExplicitOptInAndDefaultsMalformedStateToOff() throws Exception {
        Path file = tempDir.resolve("client/developer-mode");
        var settings = new ClientDeveloperMode(file);

        assertFalse(settings.enabled());
        settings.setEnabled(true);
        assertTrue(new ClientDeveloperMode(file).enabled());
        settings.setEnabled(false);
        assertFalse(new ClientDeveloperMode(file).enabled());

        Files.writeString(file, "invalid");
        assertFalse(new ClientDeveloperMode(file).enabled());
    }
}
