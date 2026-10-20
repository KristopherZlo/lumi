package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.*;
import io.github.lumi.domain.model.AxiomHistoryMode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AxiomHistoryModeRepositoryTest {
    @Test void defaultsToNativeAndKeepsTheLastChoiceOnStaleWrites(@TempDir Path root) throws Exception {
        var repository = new AxiomHistoryModeRepository(root);
        assertEquals(new AxiomHistoryModeRepository.State(AxiomHistoryMode.NATIVE_AXIOM, 0), repository.read());
        var shared = repository.write(AxiomHistoryMode.SHARED, 0);
        assertEquals(shared, new AxiomHistoryModeRepository(root).read());
        assertThrows(java.io.IOException.class, () -> repository.write(AxiomHistoryMode.NATIVE_AXIOM, 0));
        assertEquals(shared, repository.read());
        assertEquals(shared, repository.write(AxiomHistoryMode.SHARED, shared.revision()));
        repository.write(AxiomHistoryMode.NATIVE_AXIOM, shared.revision());
        var file = root.resolve("lumi/history/server/axiom-history.bin");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] = 127;
        Files.write(file, bytes);
        assertThrows(java.io.IOException.class, repository::read);
        Files.write(file, new byte[0]);
        assertThrows(java.io.IOException.class, repository::read);
    }
}
