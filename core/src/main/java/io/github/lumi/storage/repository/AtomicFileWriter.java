package io.github.lumi.storage.repository;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.locks.LockSupport;

final class AtomicFileWriter {
    private AtomicFileWriter() {
    }

    static void replace(Path target, byte[] content) throws IOException {
        replace(target, content, () -> { });
    }

    static void replace(Path target, byte[] content, FailurePoint beforeMove) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".atomic-", ".tmp");
        try {
            write(temporary, content);
            beforeMove.run();
            moveReplacement(temporary, target);
            if (!Arrays.equals(content,
                    RepositoryFileReader.read(target, content.length))) {
                throw new IOException("Atomic file verification failed: " + target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Publishes a fully encoded sibling file without retaining a second payload-sized byte array. */
    static void replacePrepared(Path target, Path temporary) throws IOException {
        target = target.toAbsolutePath().normalize();
        temporary = temporary.toAbsolutePath().normalize();
        if (target.equals(temporary) || !target.getParent().equals(temporary.getParent())
                || Files.isSymbolicLink(temporary) || !Files.isRegularFile(temporary)) {
            throw new IOException("Prepared repository file must be a regular sibling of its target");
        }
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            byte[] expected = fingerprint(temporary);
            moveReplacement(temporary, target);
            if (!MessageDigest.isEqual(expected, fingerprint(target))) {
                throw new IOException("Atomic file verification failed: " + target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveReplacement(Path temporary, Path target) throws IOException {
        try {
            retryAccessDenied(() -> Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Repository requires atomic moves: " + target, unsupported);
        }
    }

    private static byte[] fingerprint(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[16 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("File verification interrupted");
                    digest.update(buffer, 0, read);
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static boolean createOnce(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            return false;
        }
        Path temporary = Files.createTempFile(target.getParent(), ".immutable-", ".tmp");
        try {
            write(temporary, content);
            try {
                retryAccessDenied(() -> Files.move(
                        temporary, target, StandardCopyOption.ATOMIC_MOVE));
            } catch (java.nio.file.FileAlreadyExistsException racedWriter) {
                return false;
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Repository requires atomic moves: " + target, unsupported);
            }
            if (!Arrays.equals(content,
                    RepositoryFileReader.read(target, content.length))) {
                throw new IOException("Create-once file verification failed: " + target);
            }
            return true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void write(Path target, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    static void retryAccessDenied(IoAction action) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                action.run();
                return;
            } catch (AccessDeniedException denied) {
                if (attempt == 5) {
                    throw denied;
                }
                LockSupport.parkNanos(attempt * 2_000_000L);
                if (Thread.currentThread().isInterrupted()) {
                    InterruptedIOException interrupted =
                            new InterruptedIOException("Atomic move interrupted");
                    interrupted.initCause(denied);
                    throw interrupted;
                }
            }
        }
    }

    @FunctionalInterface
    interface FailurePoint {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws IOException;
    }
}
