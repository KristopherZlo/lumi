package io.github.luma.storage.repository;

import io.github.luma.LumaMod;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

final class StorageIo {

    private StorageIo() {
    }

    static void writeAtomically(Path targetFile, WriterAction writerAction) throws IOException {
        Files.createDirectories(targetFile.getParent());
        Path tempFile = targetFile.resolveSibling(targetFile.getFileName().toString() + ".tmp");
        try (OutputStream output = Files.newOutputStream(tempFile)) {
            writerAction.write(output);
        }

        try {
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void writeCompound(DataOutput output, CompoundTag tag) throws IOException {
        byte[] bytes = serializeCompound(tag);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    static CompoundTag readCompound(DataInput input) throws IOException {
        int length = StorageLimits.requireLength("NBT", input.readInt(), StorageLimits.MAX_NBT_BYTES);
        byte[] bytes = readFullyBounded(input, length, StorageLimits.MAX_NBT_BYTES, "NBT");
        return deserializeCompound(bytes);
    }

    static void writeNullableCompound(DataOutput output, CompoundTag tag) throws IOException {
        output.writeBoolean(tag != null);
        if (tag != null) {
            writeCompound(output, tag);
        }
    }

    static CompoundTag readNullableCompound(DataInput input) throws IOException {
        return input.readBoolean() ? readCompound(input) : null;
    }

    static byte[] readFullyBounded(DataInput input, int length, int maxBytes, String label) throws IOException {
        StorageLimits.requireLength(label, length, maxBytes);
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    static byte[] readAllBytesBounded(InputStream input, int maxBytes, String label) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() > maxBytes - read) {
                throw new IOException(label + " exceeds " + maxBytes + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static void quarantineCorruptedFile(Path file, Exception exception) throws IOException {
        quarantineCorruptedFile(file, exception, "malformed storage file");
    }

    static void quarantineCorruptedFile(Path file, Exception exception, String description) throws IOException {
        String corruptName = file.getFileName().toString() + ".corrupt-" + System.currentTimeMillis();
        Path corruptFile = file.resolveSibling(corruptName);
        try {
            Files.move(file, corruptFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveException) {
            Files.deleteIfExists(file);
        }
        LumaMod.LOGGER.warn("Quarantined {} {}", description, file, exception);
    }

    private static byte[] serializeCompound(CompoundTag tag) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(byteStream)) {
            NbtIo.write(tag, output);
        }
        return byteStream.toByteArray();
    }

    private static CompoundTag deserializeCompound(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.read(input, NbtAccounter.unlimitedHeap());
        }
    }

    @FunctionalInterface
    interface WriterAction {
        void write(OutputStream output) throws IOException;
    }
}
