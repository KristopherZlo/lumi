package io.github.luma.ui.overlay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingChangesOverlayCoordinatorTest {

    @Test
    void stalePreparedMeshesAreDiscarded() throws IOException {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/ui/overlay/PendingChangesOverlayCoordinator.java"
        ));

        assertTrue(source.contains("PendingChangesOverlayRenderer.discard(prepared);"));
    }
}
