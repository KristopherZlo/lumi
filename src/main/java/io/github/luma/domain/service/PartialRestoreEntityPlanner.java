package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.StoredEntityChange;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

final class PartialRestoreEntityPlanner {

    List<StoredEntityChange> plan(
            List<StoredEntityChange> pendingChanges,
            List<StoredEntityChange> reverseLineageChanges,
            List<StoredEntityChange> forwardLineageChanges,
            Bounds3i bounds,
            PartialRestoreMode mode
    ) {
        return this.plan(
                pendingChanges,
                reverseLineageChanges,
                forwardLineageChanges,
                bounds,
                mode,
                point -> true
        );
    }

    List<StoredEntityChange> plan(
            List<StoredEntityChange> pendingChanges,
            List<StoredEntityChange> reverseLineageChanges,
            List<StoredEntityChange> forwardLineageChanges,
            Bounds3i bounds,
            PartialRestoreMode mode,
            Predicate<BlockPoint> hardScope
    ) {
        PartialRestoreMode effectiveMode = mode == null ? PartialRestoreMode.SELECTED_AREA : mode;
        Predicate<BlockPoint> hardLimit = hardScope == null ? point -> true : hardScope;
        Map<String, StoredEntityChange> planned = new LinkedHashMap<>();
        for (StoredEntityChange change : pendingChanges) {
            this.accumulate(planned, change.inverse(), bounds, effectiveMode, hardLimit);
        }
        for (StoredEntityChange change : reverseLineageChanges) {
            this.accumulate(planned, change.inverse(), bounds, effectiveMode, hardLimit);
        }
        for (StoredEntityChange change : forwardLineageChanges) {
            this.accumulate(planned, change, bounds, effectiveMode, hardLimit);
        }
        return planned.values().stream()
                .filter(change -> !change.isNoOp())
                .toList();
    }

    private void accumulate(
            Map<String, StoredEntityChange> planned,
            StoredEntityChange target,
            Bounds3i bounds,
            PartialRestoreMode mode,
            Predicate<BlockPoint> hardScope
    ) {
        if (!this.includes(target, bounds, mode, hardScope)) {
            return;
        }
        StoredEntityChange current = planned.get(target.entityId());
        planned.put(target.entityId(), current == null ? target : current.withLatestState(target.newValue()));
    }

    private boolean includes(
            StoredEntityChange change,
            Bounds3i bounds,
            PartialRestoreMode mode,
            Predicate<BlockPoint> hardScope
    ) {
        if (change == null || bounds == null) {
            return false;
        }
        BlockPoint oldPoint = this.entityPoint(change.oldValue());
        BlockPoint newPoint = this.entityPoint(change.newValue());
        return this.includes(oldPoint, bounds, mode, hardScope)
                || this.includes(newPoint, bounds, mode, hardScope);
    }

    private boolean includes(
            BlockPoint point,
            Bounds3i bounds,
            PartialRestoreMode mode,
            Predicate<BlockPoint> hardScope
    ) {
        return point != null && hardScope.test(point) && mode.includes(bounds.contains(point));
    }

    private BlockPoint entityPoint(EntityPayload payload) {
        if (payload == null) {
            return null;
        }
        return BlockPoint.from(payload.blockPos());
    }
}
