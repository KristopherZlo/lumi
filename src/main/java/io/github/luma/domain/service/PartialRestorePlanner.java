package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

final class PartialRestorePlanner {

    List<StoredBlockChange> plan(
            List<StoredBlockChange> pendingDraftChanges,
            List<StoredBlockChange> lineageChanges,
            boolean applyNewValues,
            Bounds3i bounds
    ) {
        if (applyNewValues) {
            return this.plan(pendingDraftChanges, List.of(), lineageChanges, bounds, PartialRestoreMode.SELECTED_AREA);
        }
        return this.plan(pendingDraftChanges, lineageChanges, List.of(), bounds, PartialRestoreMode.SELECTED_AREA);
    }

    List<StoredBlockChange> plan(
            List<StoredBlockChange> pendingDraftChanges,
            List<StoredBlockChange> reverseLineageChanges,
            List<StoredBlockChange> forwardLineageChanges,
            Bounds3i bounds,
            PartialRestoreMode mode
    ) {
        if (bounds == null) {
            throw new IllegalArgumentException("Partial restore requires bounds");
        }
        return this.plan(
                pendingDraftChanges,
                reverseLineageChanges,
                forwardLineageChanges,
                bounds,
                mode,
                bounds::contains,
                point -> true
        );
    }

    List<StoredBlockChange> plan(
            List<StoredBlockChange> pendingDraftChanges,
            List<StoredBlockChange> reverseLineageChanges,
            List<StoredBlockChange> forwardLineageChanges,
            Bounds3i bounds,
            PartialRestoreMode mode,
            Predicate<BlockPoint> selectedScope,
            Predicate<BlockPoint> hardScope
    ) {
        if (bounds == null) {
            throw new IllegalArgumentException("Partial restore requires bounds");
        }
        PartialRestoreMode effectiveMode = mode == null ? PartialRestoreMode.SELECTED_AREA : mode;
        Predicate<BlockPoint> selected = selectedScope == null ? bounds::contains : selectedScope;
        Predicate<BlockPoint> hardLimit = hardScope == null ? point -> true : hardScope;
        Map<BlockPoint, ChangeAccumulator> changes = new LinkedHashMap<>();
        for (StoredBlockChange change : safeChanges(pendingDraftChanges)) {
            if (!includes(change.pos(), effectiveMode, selected, hardLimit)) {
                continue;
            }
            this.accumulate(changes, change.pos(), change.newValue(), change.oldValue());
        }

        for (StoredBlockChange change : safeChanges(reverseLineageChanges)) {
            if (!includes(change.pos(), effectiveMode, selected, hardLimit)) {
                continue;
            }
            this.accumulate(changes, change.pos(), change.newValue(), change.oldValue());
        }

        for (StoredBlockChange change : safeChanges(forwardLineageChanges)) {
            if (!includes(change.pos(), effectiveMode, selected, hardLimit)) {
                continue;
            }
            this.accumulate(changes, change.pos(), change.oldValue(), change.newValue());
        }

        List<StoredBlockChange> result = new ArrayList<>();
        for (ChangeAccumulator accumulator : changes.values()) {
            StoredBlockChange planned = accumulator.toChange();
            if (!planned.isNoOp()) {
                result.add(planned);
            }
        }
        return List.copyOf(result);
    }

    private static boolean includes(
            BlockPoint point,
            PartialRestoreMode mode,
            Predicate<BlockPoint> selectedScope,
            Predicate<BlockPoint> hardScope
    ) {
        return point != null && hardScope.test(point) && mode.includes(selectedScope.test(point));
    }

    private void accumulate(
            Map<BlockPoint, ChangeAccumulator> changes,
            BlockPoint pos,
            StatePayload current,
            StatePayload target
    ) {
        ChangeAccumulator accumulator = changes.computeIfAbsent(pos, ChangeAccumulator::new);
        accumulator.setCurrentIfAbsent(current);
        accumulator.setTarget(target);
    }

    private static List<StoredBlockChange> safeChanges(List<StoredBlockChange> changes) {
        return changes == null ? List.of() : changes;
    }

    private static final class ChangeAccumulator {

        private final BlockPoint pos;
        private StatePayload current;
        private StatePayload target;

        private ChangeAccumulator(BlockPoint pos) {
            this.pos = pos;
        }

        private void setCurrentIfAbsent(StatePayload current) {
            if (this.current == null) {
                this.current = current;
            }
        }

        private void setTarget(StatePayload target) {
            this.target = target;
        }

        private StoredBlockChange toChange() {
            return new StoredBlockChange(this.pos, this.current, this.target);
        }
    }
}
