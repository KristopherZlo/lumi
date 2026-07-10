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

    public SnapshotRef captureEntityCheckpoint(
            ProjectLayout layout,
            String projectId,
            String entityCheckpointId,
            Collection<ChunkPoint> chunks,
            ServerLevel level,
            Instant now
    ) throws IOException {
        List<ChunkSnapshotPayload> payloads = this.captureEntityCheckpointPayloads(level, chunks);
        return this.snapshotWriter.writePreparedSnapshot(
                layout,
                layout.entityCheckpointFile(entityCheckpointId),
                projectId,
                entityCheckpointId,
                payloads,
                now
        );
    }

    public void captureSnapshotAndEntityCheckpoint(
            ProjectLayout layout,
            String projectId,
            String snapshotId,
            String entityCheckpointId,
            Collection<ChunkPoint> chunks,
            ServerLevel level,
            Instant now
    ) throws IOException {
        List<ChunkSnapshotPayload> payloads = this.capturePayloads(level, chunks);
        this.snapshotWriter.writePreparedSnapshot(layout, projectId, snapshotId, payloads, now);
        this.snapshotWriter.writePreparedSnapshot(
                layout,
                layout.entityCheckpointFile(entityCheckpointId),
                projectId,
                entityCheckpointId,
                this.entityOnlyPayloads(payloads),
                now
        );
    }

    private List<ChunkSnapshotPayload> capturePayloads(ServerLevel level, Collection<ChunkPoint> chunks) throws IOException {
        List<ChunkSnapshotPayload> payloads = new ArrayList<>();
        for (ChunkPoint chunk : new LinkedHashSet<>(chunks == null ? List.<ChunkPoint>of() : chunks)) {
            this.throwIfInterrupted();
            this.serverThreadExecutor.call(level.getServer(), () -> {
                this.chunkSnapshotCaptureService.captureChunk(level, chunk).ifPresent(payloads::add);
                return null;
            });
        }
        LumaMod.LOGGER.info("Captured {} snapshot chunks in server-thread slices", payloads.size());
        return List.copyOf(payloads);
    }

    private List<ChunkSnapshotPayload> captureEntityCheckpointPayloads(
            ServerLevel level,
            Collection<ChunkPoint> chunks
    ) throws IOException {
        List<ChunkSnapshotPayload> payloads = new ArrayList<>();
        for (ChunkPoint chunk : new LinkedHashSet<>(chunks == null ? List.<ChunkPoint>of() : chunks)) {
            this.throwIfInterrupted();
            this.serverThreadExecutor.call(level.getServer(), () -> {
                this.chunkSnapshotCaptureService.captureEntityCheckpointChunk(level, chunk).ifPresent(payloads::add);
                return null;
            });
        }
        LumaMod.LOGGER.info("Captured {} entity checkpoint chunks in server-thread slices", payloads.size());
        return List.copyOf(payloads);
    }

    List<ChunkSnapshotPayload> entityOnlyPayloads(List<ChunkSnapshotPayload> payloads) {
        return payloads.stream()
                .map(payload -> new ChunkSnapshotPayload(
                        payload.chunkX(),
                        payload.chunkZ(),
                        payload.minBuildHeight(),
                        payload.maxBuildHeight(),
                        List.of(),
                        java.util.Map.of(),
                        payload.entitySnapshots()
                ))
                .toList();
    }

    private void throwIfInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Snapshot capture was interrupted");
        }
    }
}
