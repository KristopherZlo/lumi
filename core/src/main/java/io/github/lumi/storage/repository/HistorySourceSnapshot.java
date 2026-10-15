package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Detects changes to mutable source metadata before reusing conversion work. */
public final class HistorySourceSnapshot {
    private static final List<String> DIRECTORIES = List.of(
            "refs", "tombstones", "origins", "names", "tags", "previews", "workspaces", "working");

    public ObjectId capture(Path repository) throws IOException {
        byte[] total = new byte[32];
        byte[] buffer = new byte[16 * 1024];
        long count = 0;
        for (String name : DIRECTORIES) {
            Path directory = repository.resolve(name);
            if (!Files.exists(directory)) continue;
            try (var paths = Files.walk(directory)) {
                var iterator = paths.iterator();
                while (iterator.hasNext()) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new java.io.InterruptedIOException("History source scan interrupted");
                    }
                    Path file = iterator.next();
                    if (Files.isSymbolicLink(file)) throw new IOException("History metadata is a symbolic link: " + file);
                    if (!Files.isRegularFile(file) || file.getFileName().toString().startsWith(".")) continue;
                    MessageDigest digest = digest();
                    digest.update(repository.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    try (var input = Files.newInputStream(file)) {
                        for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
                    }
                    // Sum path-qualified hashes so filesystem order cannot change the snapshot.
                    // This cache fingerprint does not replace durable object/root verification.
                    byte[] hash = digest.digest();
                    int carry = 0;
                    for (int index = total.length - 1; index >= 0; index--) {
                        int sum = Byte.toUnsignedInt(total[index]) + Byte.toUnsignedInt(hash[index]) + carry;
                        total[index] = (byte) sum;
                        carry = sum >>> 8;
                    }
                    count++;
                }
            }
        }
        return ObjectId.hash(ByteBuffer.allocate(Long.BYTES + total.length).putLong(count).put(total).array());
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
