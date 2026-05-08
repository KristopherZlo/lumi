package io.github.luma.storage.repository;

import io.github.luma.storage.ProjectLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadContentRepositoryTest {

    @TempDir
    Path tempDir;

    private final PayloadContentRepository repository = new PayloadContentRepository();

    @Test
    void deduplicatesContentByDigest() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        byte[] payload = "same immutable section".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        var first = this.repository.writeContent(layout, "snapshot-section", payload);
        var second = this.repository.writeContent(layout, "snapshot-section", payload);

        assertEquals(first.sha256(), second.sha256());
        assertTrue(this.repository.contains(layout, first));
        assertArrayEquals(payload, this.repository.readContent(layout, first));
        assertEquals(1, Files.list(layout.contentCacheDir()).count());
    }
}
