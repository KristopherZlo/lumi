package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.*;
import io.github.lumi.domain.model.ObjectId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AxiomHistoryLinkRepositoryTest {
    @Test void retainsIdentityAndOnlyTruncatesTheReplacedRedoTail(@TempDir Path root) throws Exception {
        var repository = new AxiomHistoryLinkRepository(root);
        var link = new AxiomHistoryLinkRepository.Link(UUID.randomUUID(), UUID.randomUUID(), ObjectId.hash(new byte[] {1}));
        repository.put(1, link);
        repository.put(3, link);
        assertEquals(repository.read(), new AxiomHistoryLinkRepository(root).read());
        repository.put(2, link);
        assertEquals(java.util.Set.of(1, 2), repository.read().keySet());
        Files.write(root.resolve("lumi-links.bin"), new byte[8]);
        assertThrows(java.io.IOException.class, repository::read);
    }
}
