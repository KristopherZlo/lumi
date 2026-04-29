package io.github.luma.client.selection;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import java.util.Optional;

public final class LumiRegionSelectionState {

    private LumiRegionSelectionMode mode = LumiRegionSelectionMode.CORNERS;
    private BlockPoint cornerA;
    private BlockPoint cornerB;

    public LumiRegionSelectionMode mode() {
        return this.mode;
    }

    public void toggleMode() {
        this.mode = this.mode.toggled();
    }

    public void selectPrimary(BlockPoint point) {
        if (point == null) {
            return;
        }
        if (this.mode == LumiRegionSelectionMode.EXTEND) {
            this.extendTo(point);
            return;
        }
        this.cornerA = point;
    }

    public void selectSecondary(BlockPoint point) {
        if (point == null) {
            return;
        }
        if (this.mode == LumiRegionSelectionMode.EXTEND) {
            this.resetTo(point);
            return;
        }
        this.cornerB = point;
    }

    public void resetTo(BlockPoint point) {
        if (point == null) {
            return;
        }
        this.cornerA = point;
        this.cornerB = point;
    }

    public void clear() {
        this.cornerA = null;
        this.cornerB = null;
    }

    public Optional<Bounds3i> bounds() {
        if (this.cornerA == null && this.cornerB == null) {
            return Optional.empty();
        }
        BlockPoint first = this.cornerA == null ? this.cornerB : this.cornerA;
        BlockPoint second = this.cornerB == null ? this.cornerA : this.cornerB;
        return Optional.of(normalize(first, second));
    }

    private void extendTo(BlockPoint point) {
        Optional<Bounds3i> current = this.bounds();
        if (current.isEmpty()) {
            this.cornerA = point;
            this.cornerB = point;
            return;
        }
        Bounds3i bounds = current.get();
        this.cornerA = new BlockPoint(
                Math.min(bounds.min().x(), point.x()),
                Math.min(bounds.min().y(), point.y()),
                Math.min(bounds.min().z(), point.z())
        );
        this.cornerB = new BlockPoint(
                Math.max(bounds.max().x(), point.x()),
                Math.max(bounds.max().y(), point.y()),
                Math.max(bounds.max().z(), point.z())
        );
    }

    private static Bounds3i normalize(BlockPoint first, BlockPoint second) {
        return new Bounds3i(
                new BlockPoint(
                        Math.min(first.x(), second.x()),
                        Math.min(first.y(), second.y()),
                        Math.min(first.z(), second.z())
                ),
                new BlockPoint(
                        Math.max(first.x(), second.x()),
                        Math.max(first.y(), second.y()),
                        Math.max(first.z(), second.z())
                )
        );
    }
}
