package io.github.luma.minecraft.bootstrap;

import io.github.luma.domain.model.ChunkPoint;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

final class RegionChunkScanner {

    private static final int HEADER_BYTES = 8192;
    private static final int SECTOR_BYTES = 4096;
    private static final int CHUNK_COUNT = 1024;
    private static final int MAX_CHUNK_NBT_BYTES = 64 * 1024 * 1024;
    private static final int EXTERNAL_STREAM_FLAG = 128;

    List<RegionChunkRecord> scan(Path regionFile) throws IOException {
        RegionCoordinate region = RegionCoordinate.parse(regionFile);
        if (region == null || !Files.isRegularFile(regionFile) || Files.size(regionFile) < HEADER_BYTES) {
            return List.of();
        }

        List<RegionChunkRecord> chunks = new ArrayList<>();
        try (RandomAccessFile file = new RandomAccessFile(regionFile.toFile(), "r")) {
            for (int index = 0; index < CHUNK_COUNT; index++) {
                file.seek((long) index * Integer.BYTES);
                int location = file.readInt();
                int sector = (location >>> 8) & 0xFFFFFF;
                int sectorCount = location & 0xFF;
                if (sector < 2 || sectorCount <= 0) {
                    continue;
                }
                RegionChunkRecord record = this.readChunk(regionFile, file, region, index, sector, sectorCount);
                if (record != null) {
                    chunks.add(record);
                }
            }
        }
        return List.copyOf(chunks);
    }

    private RegionChunkRecord readChunk(
            Path regionFile,
            RandomAccessFile file,
            RegionCoordinate region,
            int index,
            int sector,
            int sectorCount
    ) throws IOException {
        long offset = (long) sector * SECTOR_BYTES;
        long maxLength = (long) sectorCount * SECTOR_BYTES;
        if (offset < HEADER_BYTES || offset + 5L > file.length()) {
            return null;
        }
        file.seek(offset);
        int length = file.readInt();
        int compression = file.readUnsignedByte();
        if (length <= 1 || length > maxLength || offset + Integer.BYTES + length > file.length()) {
            return null;
        }

        byte[] compressed = this.readCompressedPayload(regionFile, file, region, index, compression, length - 1);
        if (compressed == null) {
            return null;
        }
        int chunkX = region.x() * 32 + (index & 31);
        int chunkZ = region.z() * 32 + (index >> 5);
        byte[] nbtBytes = this.decompress(compression & ~EXTERNAL_STREAM_FLAG, compressed);
        CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbtBytes)));
        return new RegionChunkRecord(new ChunkPoint(chunkX, chunkZ), nbtBytes, tag);
    }

    private byte[] readCompressedPayload(
            Path regionFile,
            RandomAccessFile file,
            RegionCoordinate region,
            int index,
            int compression,
            int internalLength
    ) throws IOException {
        if ((compression & EXTERNAL_STREAM_FLAG) != 0) {
            int chunkX = region.x() * 32 + (index & 31);
            int chunkZ = region.z() * 32 + (index >> 5);
            Path externalFile = regionFile.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
            if (!Files.isRegularFile(externalFile)) {
                return null;
            }
            if (Files.size(externalFile) > MAX_CHUNK_NBT_BYTES) {
                throw new IOException("External region chunk exceeds " + MAX_CHUNK_NBT_BYTES + " bytes");
            }
            return Files.readAllBytes(externalFile);
        }
        byte[] compressed = new byte[internalLength];
        file.readFully(compressed);
        return compressed;
    }

    private byte[] decompress(int compression, byte[] compressed) throws IOException {
        InputStream raw = new ByteArrayInputStream(compressed);
        InputStream input = switch (compression) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            case 4 -> new LZ4BlockInputStream(raw);
            default -> throw new IOException("Unsupported region chunk compression " + compression);
        };
        try (input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() > MAX_CHUNK_NBT_BYTES - read) {
                    throw new IOException("Region chunk NBT exceeds " + MAX_CHUNK_NBT_BYTES + " bytes");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    record RegionChunkRecord(ChunkPoint chunk, byte[] nbtBytes, CompoundTag tag) {

        RegionChunkRecord {
            nbtBytes = nbtBytes == null ? new byte[0] : nbtBytes.clone();
        }

        @Override
        public byte[] nbtBytes() {
            return this.nbtBytes.clone();
        }
    }

    private record RegionCoordinate(int x, int z) {

        private static RegionCoordinate parse(Path file) {
            if (file == null) {
                return null;
            }
            String name = file.getFileName().toString();
            if (!name.startsWith("r.") || !name.endsWith(".mca")) {
                return null;
            }
            String[] parts = name.substring(2, name.length() - 4).split("\\.");
            if (parts.length != 2) {
                return null;
            }
            try {
                return new RegionCoordinate(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            } catch (NumberFormatException exception) {
                return null;
            }
        }
    }
}
