package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.WorldInitialBackupManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorldInitialBackupRepositoryTest {

    @TempDir
    Path tempDir;

    private final WorldInitialBackupRepository repository = new WorldInitialBackupRepository();

    @Test
    void savesManifestAndCompressedChunkPayloads() throws Exception {
        WorldInitialBackupManifest manifest = new WorldInitialBackupManifest(
                WorldInitialBackupManifest.CURRENT_SCHEMA_VERSION,
                "World",
                123L,
                "test",
                Map.of(),
                Instant.parse("2026-04-20T10:00:00Z"),
                Instant.parse("2026-04-20T10:01:00Z")
        );

        this.repository.save(this.tempDir, manifest);
        this.repository.writeChunk(this.tempDir, "minecraft:overworld", new ChunkPoint(1, -2), new byte[] {1, 2, 3});

        assertTrue(this.repository.completedForSeed(this.tempDir, 123L));
        assertTrue(Files.exists(this.repository.backupRoot(this.tempDir).resolve("manifest.json")));
        assertTrue(Files.exists(this.repository.backupRoot(this.tempDir)
                .resolve("chunks")
                .resolve("minecraft_overworld")
                .resolve("chunk_1_-2.nbt.gz")));
    }

    @Test
    void refusesChunkPayloadsThatWouldExceedBudget() throws Exception {
        WorldInitialBackupRepository.ChunkWriteResult result = this.repository.writeChunk(
                this.tempDir,
                "minecraft:overworld",
                new ChunkPoint(8, 8),
                new byte[] {1, 2, 3},
                1L
        );

        assertFalse(result.written());
        assertFalse(Files.exists(this.repository.backupRoot(this.tempDir)
                .resolve("chunks")
                .resolve("minecraft_overworld")
                .resolve("chunk_8_8.nbt.gz")));
    }

    @Test
    void backupAttemptDoesNotExposeChunksBeforeCommit() throws Exception {
        WorldInitialBackupRepository.BackupAttempt attempt = this.repository.beginBackupAttempt(this.tempDir);

        this.repository.writeChunk(
                attempt,
                "minecraft:overworld",
                new ChunkPoint(1, -2),
                new byte[] {1, 2, 3},
                Long.MAX_VALUE
        );

        assertFalse(Files.exists(this.chunkFile(1, -2)));
        assertFalse(Files.exists(this.repository.backupRoot(this.tempDir).resolve("manifest.json")));

        this.repository.commitBackupAttempt(this.tempDir, attempt, this.manifest(123L));

        assertTrue(Files.exists(this.chunkFile(1, -2)));
        assertFalse(Files.exists(attempt.root()));
        assertTrue(this.repository.completedForSeed(this.tempDir, 123L));
    }

    @Test
    void committedBackupAttemptReplacesIncompletePublishedChunks() throws Exception {
        this.repository.writeChunk(this.tempDir, "minecraft:overworld", new ChunkPoint(99, 99), new byte[] {9});
        WorldInitialBackupRepository.BackupAttempt attempt = this.repository.beginBackupAttempt(this.tempDir);
        this.repository.writeChunk(
                attempt,
                "minecraft:overworld",
                new ChunkPoint(1, -2),
                new byte[] {1, 2, 3},
                Long.MAX_VALUE
        );

        this.repository.commitBackupAttempt(this.tempDir, attempt, this.manifest(123L));

        assertTrue(Files.exists(this.chunkFile(1, -2)));
        assertFalse(Files.exists(this.chunkFile(99, 99)));
    }

    @Test
    void beginBackupAttemptRecoversInterruptedReplacement() throws Exception {
        this.repository.save(this.tempDir, this.manifest(123L));
        this.repository.writeChunk(this.tempDir, "minecraft:overworld", new ChunkPoint(1, -2), new byte[] {1, 2, 3});
        Path backupRoot = this.repository.backupRoot(this.tempDir);
        String transactionId = "recover-test";
        Files.move(
                backupRoot.resolve("manifest.json"),
                backupRoot.resolve("manifest.replaced-" + transactionId + ".json")
        );
        Files.move(backupRoot.resolve("chunks"), backupRoot.resolve("chunks.replaced-" + transactionId));
        this.repository.writeChunk(this.tempDir, "minecraft:overworld", new ChunkPoint(99, 99), new byte[] {9});

        WorldInitialBackupRepository.BackupAttempt attempt = this.repository.beginBackupAttempt(this.tempDir);

        assertTrue(this.repository.completedForSeed(this.tempDir, 123L));
        assertTrue(Files.exists(this.chunkFile(1, -2)));
        assertFalse(Files.exists(this.chunkFile(99, 99)));
        assertFalse(Files.exists(backupRoot.resolve("manifest.replaced-" + transactionId + ".json")));
        assertFalse(Files.exists(backupRoot.resolve("chunks.replaced-" + transactionId)));
        assertTrue(Files.exists(attempt.root()));
        this.repository.abortBackupAttempt(attempt);
    }

    private WorldInitialBackupManifest manifest(long seed) {
        return new WorldInitialBackupManifest(
                WorldInitialBackupManifest.CURRENT_SCHEMA_VERSION,
                "World",
                seed,
                "test",
                Map.of(
                        "minecraft:overworld",
                        new WorldInitialBackupManifest.DimensionBackupSummary("minecraft:overworld", 1, 1, 0, 3L)
                ),
                Instant.parse("2026-04-20T10:00:00Z"),
                Instant.parse("2026-04-20T10:01:00Z")
        );
    }

    private Path chunkFile(int x, int z) {
        return this.repository.backupRoot(this.tempDir)
                .resolve("chunks")
                .resolve("minecraft_overworld")
                .resolve("chunk_" + x + "_" + z + ".nbt.gz");
    }
}
