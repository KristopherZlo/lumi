package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.HistoryGeneration;
import io.github.lumi.domain.model.WorldDataVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryGenerationRepositoryTest {
    @TempDir Path root;

    @Test
    void restartSelectsOnlyVerifiedPublishedHistoryAndRetainsTheSource() throws Exception {
        var repository = new HistoryGenerationRepository(root);
        Files.writeString(root.resolve("original-history"), "unchanged");
        assertEquals(root, repository.directory(repository.active()));
        var prepared = repository.prepare(HistoryGeneration.LEGACY, new WorldDataVersion(4786));
        assertThrows(IOException.class, () -> repository.publish(HistoryGeneration.LEGACY, prepared));
        assertEquals(HistoryGeneration.LEGACY, new HistoryGenerationRepository(root).active());
        repository.markVerified(HistoryGeneration.LEGACY, prepared);
        assertEquals(HistoryGeneration.LEGACY, new HistoryGenerationRepository(root).active());
        repository.publish(HistoryGeneration.LEGACY, prepared);
        assertEquals(prepared, new HistoryGenerationRepository(root).active());
        assertEquals("unchanged", Files.readString(root.resolve("original-history")));
        assertThrows(RefConflictException.class,
                () -> repository.prepare(HistoryGeneration.LEGACY, new WorldDataVersion(4903)));
        assertThrows(IOException.class,
                () -> repository.prepare(prepared, WorldDataVersion.LEGACY_12111));
    }

    @Test
    void failedAtomicPublicationLeavesOldHistoryUsableAndCanBeRetried() throws Exception {
        var repository = new HistoryGenerationRepository(root,
                () -> { throw new IOException("simulated failure before pointer replacement"); });
        var prepared = repository.prepare(HistoryGeneration.LEGACY, new WorldDataVersion(4786));
        repository.markVerified(HistoryGeneration.LEGACY, prepared);
        assertThrows(IOException.class, () -> repository.publish(HistoryGeneration.LEGACY, prepared));
        var restarted = new HistoryGenerationRepository(root);
        assertEquals(HistoryGeneration.LEGACY, restarted.active());
        restarted.publish(HistoryGeneration.LEGACY, prepared);
        assertEquals(prepared, new HistoryGenerationRepository(root).active());
    }

    @Test
    void corruptOrMissingVerificationNeverFallsBackToAnotherGeneration() throws Exception {
        var repository = new HistoryGenerationRepository(root);
        assertThrows(IOException.class,
                () -> repository.prepare(HistoryGeneration.LEGACY, new WorldDataVersion(9999)));
        var prepared = repository.prepare(HistoryGeneration.LEGACY, new WorldDataVersion(4786));
        repository.markVerified(HistoryGeneration.LEGACY, prepared);
        repository.publish(HistoryGeneration.LEGACY, prepared);
        Path verified = repository.directory(prepared).resolve("verified.bin");
        Files.write(verified, new byte[100]);
        assertThrows(IOException.class, () -> new HistoryGenerationRepository(root).active());
        Files.delete(verified);
        assertThrows(IOException.class, () -> new HistoryGenerationRepository(root).active());
    }
}
