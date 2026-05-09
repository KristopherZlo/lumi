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
}
