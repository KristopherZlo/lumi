package io.github.luma.minecraft.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionChunkScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void scansDeflatedRegionChunks() throws Exception {
        Path region = this.tempDir.resolve("r.0.0.mca");
        CompoundTag tag = new CompoundTag();
        tag.putLong("InhabitedTime", 42L);
        this.writeRegionChunk(region, tag);

        List<RegionChunkScanner.RegionChunkRecord> chunks = new RegionChunkScanner().scan(region);

        assertEquals(1, chunks.size());
        assertEquals(0, chunks.getFirst().chunk().x());
        assertEquals(0, chunks.getFirst().chunk().z());
        assertEquals(42L, chunks.getFirst().tag().getLongOr("InhabitedTime", 0L));
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
