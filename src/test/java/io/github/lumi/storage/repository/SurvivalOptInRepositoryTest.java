package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurvivalOptInRepositoryTest {
    @TempDir Path world;

    @Test
    void persistsExplicitSurvivalChoiceAcrossRepositoryInstances() throws IOException {
        UUID player = UUID.randomUUID();
        SurvivalOptInRepository repository = new SurvivalOptInRepository(world);

        assertFalse(repository.isEnabled(player));
        repository.setEnabled(player, true);
        assertTrue(new SurvivalOptInRepository(world).isEnabled(player));

        repository.setEnabled(player, false);
        assertFalse(new SurvivalOptInRepository(world).isEnabled(player));
    }

    @Test
    void rejectsCorruptSettingsInsteadOfSilentlyGrantingPermission() throws IOException {
        SurvivalOptInRepository repository = new SurvivalOptInRepository(world);
        Files.createDirectories(repository.file().getParent());
        Files.write(repository.file(), new byte[] {1, 2, 3});

        assertThrows(IOException.class, () -> repository.isEnabled(UUID.randomUUID()));
    }
}
