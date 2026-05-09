package io.github.luma.minecraft.bootstrap;

import io.github.luma.storage.repository.WorldInitialBackupRepository;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldInitialBackupServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void preOpenBackupWritesManifestAndReportsProgress() throws Exception {
        this.writeRegionChunk(this.tempDir.resolve("region").resolve("r.0.0.mca"), this.activeChunk());
        AtomicReference<WorldInitialBackupProgress> lastProgress = new AtomicReference<>();

        new WorldInitialBackupService().backupWorldRootIfNeeded(
                this.tempDir,
                "World",
                123L,
                lastProgress::set
        );

        WorldInitialBackupProgress progress = lastProgress.get();
        assertEquals(1, progress.completedChunks());
        assertEquals(1, progress.totalChunks());
        assertEquals(1, progress.backedUpChunks());
        assertTrue(new WorldInitialBackupRepository().completedForSeed(this.tempDir, 123L));
    }

    private CompoundTag activeChunk() {
        CompoundTag tag = new CompoundTag();
        ListTag blockEntities = new ListTag();
        blockEntities.add(new CompoundTag());
        tag.put("block_entities", blockEntities);
        return tag;
    }

    private void writeRegionChunk(Path region, CompoundTag tag) throws Exception {
        Files.createDirectories(region.getParent());
        byte[] nbt = this.serialize(tag);
        byte[] compressed = this.deflate(nbt);
        int length = compressed.length + 1;
        int sectors = Math.max(1, (length + 4 + 4095) / 4096);
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "rw")) {
            file.setLength((long) (2 + sectors) * 4096L);
            file.seek(0L);
            file.writeInt((2 << 8) | sectors);
            file.seek(2L * 4096L);
            file.writeInt(length);
            file.writeByte(2);
            file.write(compressed);
        }
    }

    private byte[] serialize(CompoundTag tag) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
        }
        return bytes.toByteArray();
    }

    private byte[] deflate(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(output)) {
            deflater.write(bytes);
        }
        return output.toByteArray();
    }
}
