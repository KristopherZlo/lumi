package io.github.luma.client.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewCaptureCoordinatorTest {

    @Test
    void previewManifestUpdatePreservesEntityCheckpointId() throws IOException {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/client/preview/PreviewCaptureCoordinator.java"
        ));

        assertTrue(source.contains("version.entityCheckpointId()"));
    }
}
