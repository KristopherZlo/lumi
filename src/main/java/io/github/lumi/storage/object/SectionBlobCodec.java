package io.github.lumi.storage.object;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.SectionBlob;
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
    private static final int MAX_STRING_BYTES = 1024 * 1024;
    private static final int MAX_NBT_BYTES = 16 * 1024 * 1024;

    public byte[] encode(SectionBlob section) throws IOException {
        SectionBlob.PaletteBlockStates states = section.palette();
        List<String> palette = states.palette();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
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
        }
        return bytes.toByteArray();
    }

    public SectionBlob decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a Lumi V2 section blob");
            }
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
                blockEntities.put(localIndex,
                        new CanonicalNbt(CanonicalBytes.read(input, MAX_NBT_BYTES, "block entity NBT")));
            }
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in section blob");
            }
            return SectionBlob.fromPalette(palette, states, blockEntities);
        } catch (java.io.EOFException truncated) {
            throw new IOException("Truncated section blob", truncated);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid section blob", invalid);
        }
    }

}
