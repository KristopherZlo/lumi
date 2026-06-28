package io.github.luma.client.selection;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public final class LumiRegionSelectionState {

    private static final int HISTORY_LIMIT = 64;

    private LumiRegionSelectionMode mode = LumiRegionSelectionMode.CORNERS;
    private BlockPoint cornerA;
    private BlockPoint cornerB;
    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();
    private Side lastResizeSide;
    private int lastResizeAmount;
    private boolean lastResizeCornerA;

    public enum Side {
        MIN_X,
        MAX_X,
        MIN_Y,
        MAX_Y,
        MIN_Z,
        MAX_Z
    }

    public LumiRegionSelectionMode mode() {
        return this.mode;
    }

    public void toggleMode() {
        this.change(() -> {
            this.mode = this.mode.toggled();
            this.clearResizeDrag();
        });
    }

    public void selectPrimary(BlockPoint point) {
        if (point == null) {
            return;
        }
        this.change(() -> {
            if (this.mode == LumiRegionSelectionMode.EXTEND) {
                this.extendTo(point);
            } else {
                this.cornerA = point;
            }
            this.clearResizeDrag();
        });
    }

    public void selectSecondary(BlockPoint point) {
        if (point == null) {
            return;
        }
        this.change(() -> {
            if (this.mode == LumiRegionSelectionMode.EXTEND) {
                this.resetToInternal(point);
            } else {
                this.cornerB = point;
            }
            this.clearResizeDrag();
        });
    }

    public void resetTo(BlockPoint point) {
        if (point == null) {
            return;
        }
        this.change(() -> {
            this.resetToInternal(point);
            this.clearResizeDrag();
        });
    }

    public void clear() {
        this.change(() -> {
            this.cornerA = null;
            this.cornerB = null;
            this.clearResizeDrag();
        });
    }

    public void resize(Side side, int amount) {
        if (side == null || amount == 0) {
            return;
        }
        Optional<Bounds3i> current = this.bounds();
        if (current.isEmpty()) {
            return;
        }
        Bounds3i bounds = current.get();
        this.change(() -> {
            if (this.cornerA == null) {
                this.cornerA = bounds.min();
            }
            if (this.cornerB == null) {
                this.cornerB = bounds.max();
            }
            boolean moveCornerA = this.lastResizeSide == side && this.lastResizeAmount == amount
                    ? this.lastResizeCornerA
                    : this.cornerAOn(side);
            if (moveCornerA) {
                this.cornerA = move(this.cornerA, side, amount);
            } else {
                this.cornerB = move(this.cornerB, side, amount);
            }
            this.lastResizeSide = side;
            this.lastResizeAmount = amount;
            this.lastResizeCornerA = moveCornerA;
        });
    }

    public boolean undo() {
        if (this.undo.isEmpty()) {
            return false;
        }
        this.redo.addLast(this.snapshot());
        this.restore(this.undo.removeLast());
        return true;
    }

    public boolean redo() {
        if (this.redo.isEmpty()) {
            return false;
        }
        this.pushUndo(this.snapshot());
        this.restore(this.redo.removeLast());
        return true;
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
            this.resetToInternal(point);
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

    private void resetToInternal(BlockPoint point) {
        this.cornerA = point;
        this.cornerB = point;
    }

    private void change(Runnable mutation) {
        Snapshot before = this.snapshot();
        mutation.run();
        if (!before.equals(this.snapshot())) {
            this.pushUndo(before);
            this.redo.clear();
        }
    }

    private void pushUndo(Snapshot snapshot) {
        this.undo.addLast(snapshot);
        if (this.undo.size() > HISTORY_LIMIT) {
            this.undo.removeFirst();
        }
    }

    private Snapshot snapshot() {
        return new Snapshot(this.mode, this.cornerA, this.cornerB);
    }

    private void restore(Snapshot snapshot) {
        this.mode = snapshot.mode();
        this.cornerA = snapshot.cornerA();
        this.cornerB = snapshot.cornerB();
        this.clearResizeDrag();
    }

    private boolean cornerAOn(Side side) {
        return switch (side) {
            case MIN_X -> this.cornerA.x() <= this.cornerB.x();
            case MAX_X -> this.cornerA.x() >= this.cornerB.x();
            case MIN_Y -> this.cornerA.y() <= this.cornerB.y();
            case MAX_Y -> this.cornerA.y() >= this.cornerB.y();
            case MIN_Z -> this.cornerA.z() <= this.cornerB.z();
            case MAX_Z -> this.cornerA.z() >= this.cornerB.z();
        };
    }

    private static BlockPoint move(BlockPoint point, Side side, int amount) {
        return switch (side) {
            case MIN_X -> new BlockPoint(point.x() - amount, point.y(), point.z());
            case MAX_X -> new BlockPoint(point.x() + amount, point.y(), point.z());
            case MIN_Y -> new BlockPoint(point.x(), point.y() - amount, point.z());
            case MAX_Y -> new BlockPoint(point.x(), point.y() + amount, point.z());
            case MIN_Z -> new BlockPoint(point.x(), point.y(), point.z() - amount);
            case MAX_Z -> new BlockPoint(point.x(), point.y(), point.z() + amount);
        };
    }

    private void clearResizeDrag() {
        this.lastResizeSide = null;
        this.lastResizeAmount = 0;
        this.lastResizeCornerA = false;
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

    private record Snapshot(LumiRegionSelectionMode mode, BlockPoint cornerA, BlockPoint cornerB) {
    }
}
