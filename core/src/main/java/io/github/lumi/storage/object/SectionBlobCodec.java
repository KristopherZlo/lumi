package io.github.lumi.storage.object;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.WorldDataVersion;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SectionBlobCodec {
    private static final int MAGIC = 0x4C555332;
    private static final int VERSIONED_MAGIC = 0x4C555333;
    private static final int ACTION_MAGIC = 0x4C555334;
    private static final int MAX_STRING_BYTES = 1024 * 1024;
    private static final int MAX_NBT_BYTES = 16 * 1024 * 1024;
    private final CanonicalNbt.Store spool;

    public SectionBlobCodec() { this(null); }
    public SectionBlobCodec(CanonicalNbt.Store spool) { this.spool = spool; }

    public byte[] encode(SectionBlob section) throws IOException {
        SectionBlob.PaletteBlockStates states = section.palette();
        List<String> palette = states.palette();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            boolean action = !section.actionBiomes().isEmpty();
            boolean legacy = !action && section.dataVersion().equals(WorldDataVersion.LEGACY_12111);
            output.writeInt(action ? ACTION_MAGIC : legacy ? MAGIC : VERSIONED_MAGIC);
            if (!legacy) output.writeInt(section.dataVersion().value());
            output.writeInt(palette.size());
            for (String state : palette) {
                CanonicalBytes.writeString(output, state, MAX_STRING_BYTES, "block state");
            }
            for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
                output.writeShort(states.paletteIndex(index));
            }
            output.writeInt(section.blockEntities().size());
            for (var entry : new java.util.TreeMap<>(section.blockEntities()).entrySet()) {
                output.writeShort(entry.getKey());
                CanonicalBytes.write(output, entry.getValue().bytes(), MAX_NBT_BYTES, "block entity NBT");
            }
            if (action) {
                output.writeByte(section.actionBiomes().size());
                for (var entry : new java.util.TreeMap<>(section.actionBiomes()).entrySet()) {
                    output.writeByte(entry.getKey());
                    CanonicalBytes.writeString(output, entry.getValue(), 1024, "action biome");
                }
            }
        }
        return bytes.toByteArray();
    }

    public SectionBlob decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int magic = input.readInt();
            if (magic != MAGIC && magic != VERSIONED_MAGIC && magic != ACTION_MAGIC) {
                throw new IOException("Not a Lumi section blob");
            }
            WorldDataVersion dataVersion = magic == MAGIC ? WorldDataVersion.LEGACY_12111
                    : new WorldDataVersion(input.readInt());
            int paletteSize = input.readInt();
            if (paletteSize < 1 || paletteSize > SectionBlob.BLOCK_COUNT) {
                throw new IOException("Invalid section palette size");
            }
            List<String> palette = new ArrayList<>(paletteSize);
            for (int index = 0; index < paletteSize; index++) {
                palette.add(CanonicalBytes.readString(input, MAX_STRING_BYTES, "block state"));
            }

            short[] states = new short[SectionBlob.BLOCK_COUNT];
            for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
                int paletteIndex = input.readUnsignedShort();
                if (paletteIndex >= paletteSize) {
                    throw new IOException("Block state references missing palette entry");
                }
                states[index] = (short) paletteIndex;
            }

            int blockEntityCount = input.readInt();
            if (blockEntityCount < 0 || blockEntityCount > SectionBlob.BLOCK_COUNT) {
                throw new IOException("Invalid block entity count");
            }
            Map<Integer, CanonicalNbt> blockEntities = new LinkedHashMap<>();
            int previousIndex = -1;
            for (int index = 0; index < blockEntityCount; index++) {
                int localIndex = input.readUnsignedShort();
                if (localIndex <= previousIndex || localIndex >= SectionBlob.BLOCK_COUNT) {
                    throw new IOException("Block entities are not in canonical order");
                }
                previousIndex = localIndex;
                var nbt = new CanonicalNbt(CanonicalBytes.read(input, MAX_NBT_BYTES, "block entity NBT"));
                blockEntities.put(localIndex, spool == null ? nbt : spool.store(nbt));
            }
            var biomes = new LinkedHashMap<Integer, String>();
            if (magic == ACTION_MAGIC) {
                int count = input.readUnsignedByte();
                if (count < 1 || count > 64) throw new IOException("Invalid action biome count");
                int previous = -1;
                for (int index = 0; index < count; index++) {
                    int cell = input.readUnsignedByte();
                    if (cell <= previous || cell >= 64) throw new IOException("Noncanonical action biome order");
                    previous = cell;
                    biomes.put(cell, CanonicalBytes.readString(input, 1024, "action biome"));
                }
            }
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in section blob");
            }
            return SectionBlob.fromPalette(palette, states, blockEntities, dataVersion).withActionBiomes(biomes);
        } catch (java.io.EOFException truncated) {
            throw new IOException("Truncated section blob", truncated);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid section blob", invalid);
        }
    }

}
