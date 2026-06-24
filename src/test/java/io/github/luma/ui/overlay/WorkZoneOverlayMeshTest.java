package io.github.luma.ui.overlay;

import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.service.WorkZoneShellPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
