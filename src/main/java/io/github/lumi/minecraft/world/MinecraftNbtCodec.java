package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.CanonicalNbt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/** Standard binary NBT with lexical compound ordering at every nesting level. */
public final class MinecraftNbtCodec {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DEPTH = 512;

    private MinecraftNbtCodec() { }

    public static CanonicalNbt encode(CompoundTag tag) throws IOException {
        Objects.requireNonNull(tag, "tag");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(Tag.TAG_COMPOUND);
            output.writeUTF("");
            writePayload(tag, output, 0);
        }
        if (bytes.size() > MAX_BYTES) {
            throw new IOException("Canonical NBT exceeds 16 MiB");
        }
        return new CanonicalNbt(bytes.toByteArray());
    }

    public static CompoundTag decode(CanonicalNbt canonical) throws IOException {
        byte[] bytes = Objects.requireNonNull(canonical, "canonical").bytes();
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Canonical NBT exceeds 16 MiB");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            CompoundTag decoded = NbtIo.read(input, NbtAccounter.create(MAX_BYTES));
            if (input.available() != 0) {
                throw new IOException("Trailing canonical NBT bytes");
            }
            return decoded;
        }
    }

    private static void writePayload(Tag tag, DataOutput output, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting exceeds " + MAX_DEPTH);
        }
        switch (tag.getId()) {
            case Tag.TAG_COMPOUND -> writeCompound((CompoundTag) tag, output, depth);
            case Tag.TAG_LIST -> writeList((ListTag) tag, output, depth);
            case Tag.TAG_BYTE, Tag.TAG_SHORT, Tag.TAG_INT, Tag.TAG_LONG,
                    Tag.TAG_FLOAT, Tag.TAG_DOUBLE, Tag.TAG_BYTE_ARRAY, Tag.TAG_STRING,
                    Tag.TAG_INT_ARRAY, Tag.TAG_LONG_ARRAY -> tag.write(output);
            default -> throw new IOException("Unsupported NBT tag type: " + tag.getId());
        }
    }

    private static void writeCompound(CompoundTag compound, DataOutput output, int depth)
            throws IOException {
        var keys = new ArrayList<>(compound.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            Tag child = Objects.requireNonNull(compound.get(key), "compound child");
            if (child.getId() == Tag.TAG_END) {
                throw new IOException("NBT compounds cannot contain end tags");
            }
            output.writeByte(child.getId());
            output.writeUTF(key);
            writePayload(child, output, depth + 1);
        }
        output.writeByte(Tag.TAG_END);
    }

    private static void writeList(ListTag list, DataOutput output, int depth) throws IOException {
        byte elementType = list.isEmpty() ? Tag.TAG_END : list.getFirst().getId();
        output.writeByte(elementType);
        output.writeInt(list.size());
        for (Tag element : list) {
            if (element.getId() != elementType || elementType == Tag.TAG_END) {
                throw new IOException("NBT list contains inconsistent element types");
            }
            writePayload(element, output, depth + 1);
        }
    }
}
