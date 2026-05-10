package io.github.luma.minecraft.bootstrap;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.WorldInitialBackupManifest;
import io.github.luma.storage.repository.WorldInitialBackupRepository;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldInitialBackupRestoreServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void restoresStoredChunkPayloadsIntoRegionFiles() throws Exception {
        WorldInitialBackupRepository repository = new WorldInitialBackupRepository();
        ChunkPoint chunk = new ChunkPoint(2, 3);
        repository.writeChunk(this.tempDir, "minecraft:overworld", chunk, this.chunkBytes("before-lumi"));
        repository.save(this.tempDir, new WorldInitialBackupManifest(
                WorldInitialBackupManifest.CURRENT_SCHEMA_VERSION,
                "Test",
                123L,
                "test",
                128L * 1024L * 1024L,
                Map.of("minecraft:overworld", new WorldInitialBackupManifest.DimensionBackupSummary(
                        "minecraft:overworld",
                        1,
                        1,
                        0,
                        0L
                )),
                Instant.parse("2026-05-10T10:00:00Z"),
                Instant.parse("2026-05-10T10:00:01Z")
        ));
        WorldInitialBackupRestoreService service = new WorldInitialBackupRestoreService(repository);

        assertTrue(service.hasRestorableBackup(this.tempDir));
        WorldInitialBackupRestoreService.RestoreResult result = service.restore(this.tempDir);

        assertEquals(1, result.restoredChunks());
        List<RegionChunkScanner.RegionChunkRecord> chunks =
                new RegionChunkScanner().scan(this.tempDir.resolve("region").resolve("r.0.0.mca"));
        assertEquals(1, chunks.size());
        assertEquals(chunk, chunks.getFirst().chunk());
        assertEquals("before-lumi", chunks.getFirst().tag().getStringOr("lumi_marker", ""));
    }

    private byte[] chunkBytes(String marker) throws Exception {
        CompoundTag tag = new CompoundTag();
        tag.putString("lumi_marker", marker);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
        }
        return bytes.toByteArray();
    }
}
