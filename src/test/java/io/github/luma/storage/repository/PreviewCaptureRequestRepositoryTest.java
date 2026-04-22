package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PreviewCaptureRequest;
import io.github.luma.storage.ProjectLayout;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewCaptureRequestRepositoryTest {

    @TempDir
    Path tempDir;

    private final PreviewCaptureRequestRepository repository = new PreviewCaptureRequestRepository();
    private final ProjectRepository projectRepository = new ProjectRepository();

    @Test
    void savesLoadsListsAndDeletesPendingRequests() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("tower.mbp"));
        this.projectRepository.initializeLayout(layout);

        PreviewCaptureRequest older = request(
                "v0001",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 80, 15)),
                Instant.parse("2026-04-22T08:00:00Z")
        );
        PreviewCaptureRequest newer = request(
                "v0002",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(16, 64, 16), new BlockPoint(31, 90, 31)),
                Instant.parse("2026-04-22T08:05:00Z")
        );

        this.repository.save(layout, newer);
        this.repository.save(layout, older);

        assertEquals(older, this.repository.load(layout, "v0001").orElseThrow());
        assertEquals(newer, this.repository.load(layout, "v0002").orElseThrow());
        assertEquals(Path.of("preview-requests", "v0001.json"), layout.root().relativize(layout.previewRequestFile("v0001")));
        assertEquals(java.util.List.of(older, newer), this.repository.loadAll(layout));

        this.repository.delete(layout, "v0001");

        assertFalse(this.repository.load(layout, "v0001").isPresent());
        assertTrue(this.repository.load(layout, "v0002").isPresent());
    }

    private static PreviewCaptureRequest request(String versionId, String dimensionId, Bounds3i bounds, Instant requestedAt) {
        return new PreviewCaptureRequest(versionId, dimensionId, bounds, requestedAt);
    }
}
