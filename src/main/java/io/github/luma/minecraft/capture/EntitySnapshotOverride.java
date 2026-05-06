package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.ChunkPoint;
import java.util.ArrayList;
import java.util.List;

record EntitySnapshotOverride(
        EntityPayload oldPayload,
        EntityPayload newPayload
) {

    static EntitySnapshotOverride none() {
        return new EntitySnapshotOverride(null, null);
    }

    List<EntityPayload> applyTo(List<EntityPayload> capturedSnapshots) {
        return this.applyTo(capturedSnapshots, null);
    }

    List<EntityPayload> applyTo(List<EntityPayload> capturedSnapshots, ChunkPoint chunk) {
        List<EntityPayload> snapshots = new ArrayList<>();
        String overriddenEntityId = this.entityId();

        for (EntityPayload snapshot : capturedSnapshots == null ? List.<EntityPayload>of() : capturedSnapshots) {
            if (snapshot == null) {
                continue;
            }
            if (!overriddenEntityId.isBlank() && overriddenEntityId.equals(snapshot.entityId())) {
                continue;
            }
            snapshots.add(new EntityPayload(snapshot.copyTag()));
        }

        if (this.oldPayload != null && this.belongsToChunk(this.oldPayload, chunk)) {
            snapshots.add(new EntityPayload(this.oldPayload.copyTag()));
        }
        return List.copyOf(snapshots);
    }

    private boolean belongsToChunk(EntityPayload payload, ChunkPoint chunk) {
        return chunk == null || (payload != null && chunk.equals(payload.chunk()));
    }

    private String entityId() {
        if (this.newPayload != null && !this.newPayload.entityId().isBlank()) {
            return this.newPayload.entityId();
        }
        return this.oldPayload == null ? "" : this.oldPayload.entityId();
    }
}
