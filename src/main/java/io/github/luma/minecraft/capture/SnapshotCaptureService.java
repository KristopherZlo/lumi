package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.SnapshotRef;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.SnapshotWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/**
 * Captures snapshot payloads on the server thread and persists them off-thread.
 */
public final class SnapshotCaptureService {

    private final ChunkSnapshotCaptureService chunkSnapshotCaptureService = new ChunkSnapshotCaptureService();
    private final ServerThreadExecutor serverThreadExecutor = new ServerThreadExecutor();
    private final SnapshotWriter snapshotWriter = new SnapshotWriter();

    public SnapshotRef capture(
            ProjectLayout layout,
            String projectId,
            String snapshotId,
            Collection<ChunkPoint> chunks,
            ServerLevel level,
            Instant now
    ) throws IOException {
        List<ChunkSnapshotPayload> payloads = this.capturePayloads(level, chunks);
        return this.snapshotWriter.writePreparedSnapshot(layout, projectId, snapshotId, payloads, now);
    }

    private List<ChunkSnapshotPayload> capturePayloads(ServerLevel level, Collection<ChunkPoint> chunks) throws IOException {
        return this.serverThreadExecutor.call(level.getServer(), () -> {
            List<ChunkSnapshotPayload> payloads = new ArrayList<>();
            for (ChunkPoint chunk : new LinkedHashSet<>(chunks == null ? List.<ChunkPoint>of() : chunks)) {
                this.chunkSnapshotCaptureService.captureChunk(level, chunk).ifPresent(payloads::add);
            }
            LumaMod.LOGGER.info("Captured {} snapshot chunks on the server thread", payloads.size());
            return List.copyOf(payloads);
        });
    }
}
