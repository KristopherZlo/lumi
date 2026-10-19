package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void failureAfterForceButBeforeMoveKeepsPublishedFile() throws IOException {
        Path target = tempDir.resolve("refs/main.ref");
        byte[] published = new byte[] {1};
        AtomicFileWriter.replace(target, published);

        assertThrows(IOException.class, () -> AtomicFileWriter.replace(
                target, new byte[] {2}, () -> {
                    throw new IOException("injected crash");
                }));

        assertArrayEquals(published, Files.readAllBytes(target));
    }

    @Test
    void retriesTransientWindowsSharingFailure() throws IOException {
        AtomicInteger attempts = new AtomicInteger();

        AtomicFileWriter.retryAccessDenied(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new AccessDeniedException("index.bin");
            }
        });

        org.junit.jupiter.api.Assertions.assertEquals(3, attempts.get());
    }

    @Test
    void publishesPreparedPayloadAndRejectsAnotherDirectory() throws IOException {
        Path directory = Files.createDirectories(tempDir.resolve("actions"));
        Path target = directory.resolve("one.bin");
        byte[] payload = new byte[128 * 1024];
        new java.util.Random(41).nextBytes(payload);
        Path prepared = Files.write(directory.resolve(".prepared.tmp"), payload);
        AtomicFileWriter.replacePrepared(target, prepared);
        assertArrayEquals(payload, Files.readAllBytes(target));

        Path foreign = Files.write(tempDir.resolve("foreign.bin"), new byte[] {9});
        assertThrows(IOException.class, () -> AtomicFileWriter.replacePrepared(target, foreign));
        assertArrayEquals(payload, Files.readAllBytes(target));
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(foreign));
    }
}
