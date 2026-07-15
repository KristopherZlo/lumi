package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
