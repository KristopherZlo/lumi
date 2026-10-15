package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.lumi.domain.model.HistoryGeneration;
import io.github.lumi.domain.model.ObjectId;
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
        assertEquals(root, repository.currentDirectory(WorldDataVersion.LEGACY_12111));
        assertThrows(IOException.class, () -> repository.currentDirectory(new WorldDataVersion(4786)));
        var prepared = repository.prepare(HistoryGeneration.LEGACY, new WorldDataVersion(4786));
        assertThrows(IOException.class, () -> repository.publish(HistoryGeneration.LEGACY, prepared));
        assertEquals(HistoryGeneration.LEGACY, new HistoryGenerationRepository(root).active());
        repository.markVerified(HistoryGeneration.LEGACY, prepared);
        assertEquals(HistoryGeneration.LEGACY, new HistoryGenerationRepository(root).active());
        repository.publish(HistoryGeneration.LEGACY, prepared);
        assertEquals(prepared, new HistoryGenerationRepository(root).active());
        assertEquals(repository.directory(prepared), repository.currentDirectory(new WorldDataVersion(4786)));
        assertThrows(IOException.class, () -> repository.currentDirectory(WorldDataVersion.LEGACY_12111));
        assertEquals("unchanged", Files.readString(root.resolve("original-history")));
        assertThrows(IOException.class, () -> repository.markVerified(prepared,
                new HistoryGeneration(prepared.id(), new WorldDataVersion(4903))));
        assertEquals(prepared, repository.active());
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

    @Test
    void restartReusesTheJournaledTargetAndCompletesAfterPointerPublication() throws Exception {
        var repository = new HistoryGenerationRepository(root);
        var version = new WorldDataVersion(4786);
        var snapshot = ObjectId.hash(new byte[] {1});
        var prepared = repository.beginConversion(HistoryGeneration.LEGACY, version, snapshot, snapshot);
        var restarted = new HistoryGenerationRepository(root);
        assertEquals(prepared, restarted.beginConversion(HistoryGeneration.LEGACY, version, snapshot, snapshot));
        assertThrows(IOException.class, () -> restarted.beginConversion(HistoryGeneration.LEGACY, new WorldDataVersion(4903), snapshot, snapshot));
        assertThrows(RefConflictException.class, () -> restarted.finishConversion(prepared));
        restarted.markVerified(HistoryGeneration.LEGACY, prepared);
        restarted.publish(HistoryGeneration.LEGACY, prepared);
        new HistoryGenerationRepository(root).finishConversion(prepared);
        assertEquals(java.util.Optional.empty(), repository.pending());
        assertEquals(prepared, repository.active());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"source-state.bin", "conversion-recipe.bin"})
    void interruptedTargetWithoutItsResumeIdentityIsReplacedWithoutDeletingHistory(String missing) throws Exception {
        var repository = new HistoryGenerationRepository(root);
        var snapshot = ObjectId.hash(new byte[] {1});
        var version = new WorldDataVersion(4786);
        var old = repository.beginConversion(HistoryGeneration.LEGACY, version, snapshot, snapshot);
        Files.delete(repository.directory(old).resolve(missing));
        var next = new HistoryGenerationRepository(root).beginConversion(HistoryGeneration.LEGACY, version, snapshot, snapshot);
        assertNotEquals(old, next);
        assertEquals(HistoryGeneration.LEGACY, repository.active());
        assertEquals(next, repository.pending().orElseThrow().target());
        org.junit.jupiter.api.Assertions.assertTrue(Files.isDirectory(repository.directory(old)));
    }
}
