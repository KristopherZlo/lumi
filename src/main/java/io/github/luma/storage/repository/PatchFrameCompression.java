package io.github.luma.storage.repository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

final class PatchFrameCompression {

    byte[] compress(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream compressed = new LZ4FrameOutputStream(output)) {
            compressed.write(bytes);
        }
        return output.toByteArray();
    }

    byte[] decompress(byte[] bytes, int expectedLength) throws IOException {
        StorageLimits.requireLength(
                "patch chunk frame uncompressed",
                expectedLength,
                StorageLimits.MAX_PATCH_FRAME_UNCOMPRESSED_BYTES
        );
        try (LZ4FrameInputStream input = new LZ4FrameInputStream(new ByteArrayInputStream(bytes))) {
            byte[] decompressed = StorageIo.readAllBytesBounded(
                    input,
                    StorageLimits.MAX_PATCH_FRAME_UNCOMPRESSED_BYTES,
                    "decompressed patch frame"
            );
            if (decompressed.length != expectedLength) {
                throw new IOException("Patch chunk frame length mismatch");
            }
            return decompressed;
        } catch (IOException exception) {
            throw new IOException("Patch chunk frame length mismatch");
        }
    }
}
