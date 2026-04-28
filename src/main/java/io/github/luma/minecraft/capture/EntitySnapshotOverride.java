package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
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

        if (this.oldPayload != null) {
            snapshots.add(new EntityPayload(this.oldPayload.copyTag()));
        }
        return List.copyOf(snapshots);
    }

    private String entityId() {
        if (this.newPayload != null && !this.newPayload.entityId().isBlank()) {
            return this.newPayload.entityId();
        }
        return this.oldPayload == null ? "" : this.oldPayload.entityId();
    }
}
