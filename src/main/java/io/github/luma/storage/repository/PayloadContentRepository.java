package io.github.luma.storage.repository;

import io.github.luma.domain.model.ContentRef;
import io.github.luma.storage.ProjectLayout;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

public final class PayloadContentRepository {

    public ContentRef writeContent(ProjectLayout layout, String logicalKind, byte[] uncompressedBytes) throws IOException {
        byte[] payload = uncompressedBytes == null ? new byte[0] : uncompressedBytes.clone();
        String sha256 = sha256(payload);
        byte[] compressed = this.compress(payload);
        if (!Files.exists(layout.contentFile(sha256))) {
            StorageIo.writeAtomically(layout.contentFile(sha256), output -> output.write(compressed));
        }
        return new ContentRef(sha256, logicalKind, payload.length, compressed.length);
    }

    public byte[] readContent(ProjectLayout layout, ContentRef ref) throws IOException {
        if (ref == null || ref.sha256().isBlank()) {
            return new byte[0];
        }
        byte[] compressed = Files.readAllBytes(layout.contentFile(ref.sha256()));
        try (LZ4FrameInputStream input = new LZ4FrameInputStream(new ByteArrayInputStream(compressed))) {
            byte[] decompressed = StorageIo.readAllBytesBounded(
                    input,
                    StorageLimits.MAX_SNAPSHOT_FRAME_UNCOMPRESSED_BYTES,
                    "content payload"
            );
            if (ref.uncompressedBytes() >= 0 && decompressed.length != ref.uncompressedBytes()) {
                throw new IOException("Content payload length mismatch for " + ref.sha256());
            }
            if (!sha256(decompressed).equals(ref.sha256())) {
                throw new IOException("Content payload digest mismatch for " + ref.sha256());
            }
            return decompressed;
        }
    }

    public boolean contains(ProjectLayout layout, ContentRef ref) {
        return ref != null && !ref.sha256().isBlank() && Files.exists(layout.contentFile(ref.sha256()));
    }

    private byte[] compress(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream compressed = new LZ4FrameOutputStream(output)) {
            compressed.write(bytes);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(Character.forDigit((value >>> 4) & 15, 16));
                builder.append(Character.forDigit(value & 15, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
