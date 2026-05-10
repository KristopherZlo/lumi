package io.github.luma.minecraft.bootstrap;

import io.github.luma.storage.repository.WorldInitialBackupRepository;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void preOpenBackupCoalescesLargeProgressUpdates() throws Exception {
        this.writeRegionChunks(
                this.tempDir.resolve("region").resolve("r.0.0.mca"),
                Collections.nCopies(40, this.activeChunk())
        );
        AtomicInteger updates = new AtomicInteger();
        AtomicReference<WorldInitialBackupProgress> lastProgress = new AtomicReference<>();

        new WorldInitialBackupService().backupWorldRootIfNeeded(
                this.tempDir,
                "World",
                123L,
                progress -> {
                    updates.incrementAndGet();
                    lastProgress.set(progress);
                }
        );

        assertEquals(3, updates.get());
        assertEquals(40, lastProgress.get().completedChunks());
        assertEquals(40, lastProgress.get().totalChunks());
        assertEquals(40, lastProgress.get().backedUpChunks());
    }

    private CompoundTag activeChunk() {
        CompoundTag tag = new CompoundTag();
        ListTag blockEntities = new ListTag();
        blockEntities.add(new CompoundTag());
        tag.put("block_entities", blockEntities);
        return tag;
    }

    private void writeRegionChunk(Path region, CompoundTag tag) throws Exception {
        this.writeRegionChunks(region, List.of(tag));
    }

    private void writeRegionChunks(Path region, List<CompoundTag> tags) throws Exception {
        Files.createDirectories(region.getParent());
        List<byte[]> compressedChunks = tags.stream()
                .map(tag -> {
                    try {
                        return this.deflate(this.serialize(tag));
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .toList();
        int totalSectors = 2;
        for (byte[] compressed : compressedChunks) {
            int length = compressed.length + 1;
            totalSectors += Math.max(1, (length + 4 + 4095) / 4096);
        }
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "rw")) {
            file.setLength((long) totalSectors * 4096L);
            int nextSector = 2;
            for (int index = 0; index < compressedChunks.size(); index++) {
                byte[] compressed = compressedChunks.get(index);
                int length = compressed.length + 1;
                int sectors = Math.max(1, (length + 4 + 4095) / 4096);
                file.seek((long) index * Integer.BYTES);
                file.writeInt((nextSector << 8) | sectors);
                file.seek((long) nextSector * 4096L);
                file.writeInt(length);
                file.writeByte(2);
                file.write(compressed);
                nextSector += sectors;
            }
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
