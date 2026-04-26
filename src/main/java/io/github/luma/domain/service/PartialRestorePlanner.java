package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PartialRestorePlanner {

    List<StoredBlockChange> plan(
            List<StoredBlockChange> pendingDraftChanges,
            List<StoredBlockChange> lineageChanges,
            boolean applyNewValues,
            Bounds3i bounds
    ) {
        if (bounds == null) {
            throw new IllegalArgumentException("Partial restore requires bounds");
        }

        Map<BlockPoint, ChangeAccumulator> changes = new LinkedHashMap<>();
        for (StoredBlockChange change : safeChanges(pendingDraftChanges)) {
            if (!bounds.contains(change.pos())) {
                continue;
            }
            this.accumulate(changes, change.pos(), change.newValue(), change.oldValue());
        }

        for (StoredBlockChange change : safeChanges(lineageChanges)) {
            if (!bounds.contains(change.pos())) {
                continue;
            }
            StatePayload current = applyNewValues ? change.oldValue() : change.newValue();
            StatePayload target = applyNewValues ? change.newValue() : change.oldValue();
            this.accumulate(changes, change.pos(), current, target);
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
