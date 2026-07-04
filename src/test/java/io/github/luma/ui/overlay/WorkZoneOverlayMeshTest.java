package io.github.luma.ui.overlay;

import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.service.WorkZoneShellPlanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkZoneOverlayMeshTest {

    @Test
    void shellFacesBecomeMeshPrimitivesWithoutVolumeBoxes() {
        OverlayMeshBatch.Builder builder = OverlayMeshBatch.builder();
        for (var face : new WorkZoneShellPlanner().plan(List.of(
                new WorkZoneCell(0, 0, 0),
                new WorkZoneCell(1, 0, 0)
        ))) {
            builder.addShellFace(face, 255, 128, 64, 30, 0xFFFF8040, 2.0F, 0.01F);
        }

        OverlayMeshBatch batch = builder.build();

        assertEquals(6, batch.primitiveCountForTest());
    }

    @Test
    void rendererKeepsShellOutsetNonZeroForVisibleOutlines() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/overlay/WorkZoneOverlayRenderer.java"));

        assertTrue(source.contains("private static final float OUTSET = 0.01F;"));
    }

    @Test
    void mergedSurfaceKeepsBlockSquareOutlinesWithoutClippingVisibleSections() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/overlay/OverlayMeshBatch.java"));

        assertTrue(source.contains("emitSquareOutlines("));
        assertTrue(!source.contains("subList(0, drawLimit)"));
    }
}
