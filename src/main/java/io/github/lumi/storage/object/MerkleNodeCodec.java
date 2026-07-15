package io.github.lumi.storage.object;

import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class MerkleNodeCodec {
    private static final int CHUNK_MAGIC = 0x4C554332;
    private static final int REGION_MAGIC = 0x4C555232;
    private static final int DIMENSION_MAGIC = 0x4C554432;

    public byte[] encode(ChunkTree chunk) throws IOException {
        return encode(CHUNK_MAGIC, output -> {
            output.writeInt(chunk.sections().size());
            for (var entry : new TreeMap<>(chunk.sections()).entrySet()) {
                output.writeInt(entry.getKey());
                writeId(output, entry.getValue());
            }
            output.writeBoolean(chunk.entities().isPresent());
            if (chunk.entities().isPresent()) {
                writeId(output, chunk.entities().orElseThrow());
            }
        });
    }

    public byte[] encode(RegionTree region) throws IOException {
        return encode(REGION_MAGIC, output -> {
            output.writeInt(region.chunks().size());
            for (var entry : new TreeMap<>(region.chunks()).entrySet()) {
                output.writeByte(entry.getKey().x());
                output.writeByte(entry.getKey().z());
                writeId(output, entry.getValue());
            }
        });
    }

    public byte[] encode(DimensionTree dimension) throws IOException {
        return encode(DIMENSION_MAGIC, output -> {
            output.writeInt(dimension.regions().size());
            for (var entry : new TreeMap<>(dimension.regions()).entrySet()) {
                output.writeInt(entry.getKey().x());
                output.writeInt(entry.getKey().z());
                writeId(output, entry.getValue());
            }
        });
    }

    public ChunkTree decodeChunk(byte[] payload) throws IOException {
        try (DataInputStream input = input(payload, CHUNK_MAGIC)) {
            int count = count(input, 4096, "section");
            Map<Integer, ObjectId> sections = new LinkedHashMap<>();
            int previous = Integer.MIN_VALUE;
            for (int index = 0; index < count; index++) {
                int sectionY = input.readInt();
                if (index > 0 && sectionY <= previous) {
                    throw new IOException("Sections are not in canonical order");
                }
                previous = sectionY;
                sections.put(sectionY, readId(input));
            }
            int entityFlag = input.readUnsignedByte();
            if (entityFlag > 1) {
                throw new IOException("Invalid entity object flag");
            }
            Optional<ObjectId> entities = entityFlag == 1 ? Optional.of(readId(input)) : Optional.empty();
            finish(input);
            return new ChunkTree(sections, entities);
        }
    }

    public RegionTree decodeRegion(byte[] payload) throws IOException {
        try (DataInputStream input = input(payload, REGION_MAGIC)) {
            int count = count(input, 1024, "chunk");
            Map<ChunkInRegion, ObjectId> chunks = new LinkedHashMap<>();
            ChunkInRegion previous = null;
            for (int index = 0; index < count; index++) {
                ChunkInRegion coordinate = new ChunkInRegion(input.readUnsignedByte(), input.readUnsignedByte());
                if (previous != null && previous.compareTo(coordinate) >= 0) {
                    throw new IOException("Chunks are not in canonical order");
                }
                previous = coordinate;
                chunks.put(coordinate, readId(input));
            }
            finish(input);
            return new RegionTree(chunks);
        }
    }

    public DimensionTree decodeDimension(byte[] payload) throws IOException {
        try (DataInputStream input = input(payload, DIMENSION_MAGIC)) {
            int count = count(input, 1_000_000, "region");
            Map<RegionCoordinate, ObjectId> regions = new LinkedHashMap<>();
            RegionCoordinate previous = null;
            for (int index = 0; index < count; index++) {
                RegionCoordinate coordinate = new RegionCoordinate(input.readInt(), input.readInt());
                if (previous != null && previous.compareTo(coordinate) >= 0) {
                    throw new IOException("Regions are not in canonical order");
                }
                previous = coordinate;
                regions.put(coordinate, readId(input));
            }
            finish(input);
            return new DimensionTree(regions);
        }
    }

    private static byte[] encode(int magic, Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(magic);
            writer.write(output);
        }
        return bytes.toByteArray();
    }

    private static DataInputStream input(byte[] payload, int expectedMagic) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
        if (input.readInt() != expectedMagic) {
            throw new IOException("Unexpected Merkle node kind");
        }
        return input;
    }

    private static int count(DataInputStream input, int maximum, String label) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw new IOException("Invalid " + label + " count");
        }
        return count;
    }

    private static void writeId(DataOutputStream output, ObjectId id) throws IOException {
        output.write(HexFormat.of().parseHex(id.hex()));
    }

    private static ObjectId readId(DataInputStream input) throws IOException {
        byte[] bytes = input.readNBytes(32);
        if (bytes.length != 32) {
            throw new IOException("Truncated object ID");
        }
        return new ObjectId(HexFormat.of().formatHex(bytes));
    }

    private static void finish(DataInputStream input) throws IOException {
        if (input.available() != 0) {
            throw new IOException("Trailing bytes in Merkle node");
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }
}
