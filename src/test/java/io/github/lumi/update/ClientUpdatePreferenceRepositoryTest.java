package io.github.lumi.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientUpdatePreferenceRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void atomicallyDismissesOnlyTheSelectedVersion() throws Exception {
        Path file = tempDir.resolve("client/update-ignore");
        ClientUpdatePreferenceRepository preferences =
                new ClientUpdatePreferenceRepository(file);

        assertFalse(preferences.ignored("1.2.3"));
        preferences.dismiss("1.2.3");

        assertTrue(preferences.ignored("1.2.3"));
        assertFalse(preferences.ignored("1.2.4"));
        assertTrue(Files.readString(file).startsWith("1.2.3"));
        assertThrows(IllegalArgumentException.class,
                () -> preferences.dismiss("bad\nversion"));
    }
}
