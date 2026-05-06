package io.github.luma.storage.repository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class FileContentHasher {

    private static final int BUFFER_SIZE = 8192;
    private static final int STABLE_READ_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MILLIS = 25L;

    FileContentHash hashStable(Path file) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= STABLE_READ_ATTEMPTS; attempt++) {
            try {
                BasicFileAttributes before = this.readRegularFileAttributes(file);
                FileContentHash hash = this.readPath(file, null, Long.MAX_VALUE, file.toString());
                BasicFileAttributes after = this.readRegularFileAttributes(file);
                if (this.sameFileState(before, after) && hash.sizeBytes() == after.size()) {
                    return hash;
                }
                lastFailure = new IOException("File changed while hashing: " + file.getFileName());
            } catch (IOException exception) {
                lastFailure = exception;
            }
            this.sleepBeforeRetry(lastFailure);
        }
        throw lastFailure;
    }

    FileContentHash copyStable(Path file, OutputStream output) throws IOException {
        BasicFileAttributes before = this.readRegularFileAttributes(file);
        FileContentHash hash = this.readPath(file, output, Long.MAX_VALUE, file.toString());
        BasicFileAttributes after = this.readRegularFileAttributes(file);
        if (!this.sameFileState(before, after) || hash.sizeBytes() != after.size()) {
            throw new IOException("File changed while copying: " + file.getFileName());
        }
        return hash;
    }

    FileContentHash copyBounded(InputStream input, OutputStream output, long maxBytes, String label) throws IOException {
        MessageDigest digest = this.sha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (total > maxBytes - read) {
                throw new IOException(label + " exceeds " + maxBytes + " bytes");
            }
            total += read;
            digest.update(buffer, 0, read);
            if (output != null) {
                output.write(buffer, 0, read);
            }
        }
        return new FileContentHash(total, HexFormat.of().formatHex(digest.digest()));
    }

    private FileContentHash readPath(Path file, OutputStream output, long maxBytes, String label) throws IOException {
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return this.copyBounded(input, output, maxBytes, label);
        }
    }

    private BasicFileAttributes readRegularFileAttributes(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("Storage payload is not a regular file: " + file.getFileName());
        }
        return attributes;
    }

    private boolean sameFileState(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && (before.fileKey() == null || before.fileKey().equals(after.fileKey()));
    }

    private void sleepBeforeRetry(IOException cause) throws IOException {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            IOException interrupted = new IOException("Interrupted while waiting for a stable file read", exception);
            interrupted.addSuppressed(cause);
            throw interrupted;
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
