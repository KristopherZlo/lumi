package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import java.time.Instant;
import java.util.Collection;

final class WorkingDraftLiveStateReconciler {

    boolean reconcileLoadedBlocks(
            CaptureSessionState session,
            Collection<ChunkPoint> loadedChunks,
            Collection<StoredBlockChange> liveTargets,
            Instant now
    ) {
        if (session == null || loadedChunks == null || loadedChunks.isEmpty()) {
            return false;
        }
        int before = session.buffer().contentFingerprint();
        session.replaceChunkChanges(loadedChunks, liveTargets, now);
        return before != session.buffer().contentFingerprint();
    }

    boolean reconcileEntities(
            CaptureSessionState session,
            Collection<StoredEntityChange> liveTargets,
            Instant now
    ) {
        if (session == null || liveTargets == null || liveTargets.isEmpty()) {
            return false;
        }
        int before = session.buffer().contentFingerprint();
        for (StoredEntityChange change : liveTargets) {
            session.buffer().addEntityChange(change, now);
        }
        return before != session.buffer().contentFingerprint();
    }
}
