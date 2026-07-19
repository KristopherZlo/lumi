package io.github.lumi.client.state;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BlockPosition;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/** One scoped selection with legacy modes, face resize and bounded undo/redo. */
final class SelectionState {
    private static final int HISTORY_LIMIT = 64;
    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>();
    private final ArrayDeque<Snapshot> redo = new ArrayDeque<>();
    private SelectionMode mode = SelectionMode.CORNERS;
    private BlockPosition first;
    private BlockPosition second;
    private Side lastResizeSide;
    private int lastResizeAmount;
    private boolean lastResizeFirst;

    SelectionMode mode() {
        return mode;
    }

    void toggleMode() {
        change(() -> mode = mode.toggled());
        clearResizeDrag();
    }

    void selectPrimary(BlockPosition point) {
        Objects.requireNonNull(point, "point");
        change(() -> {
            if (mode == SelectionMode.EXTEND) {
                extend(point);
            } else {
                first = point;
            }
        });
        clearResizeDrag();
    }

    void selectSecondary(BlockPosition point) {
        Objects.requireNonNull(point, "point");
        change(() -> {
            if (mode == SelectionMode.EXTEND) {
                first = point;
                second = point;
            } else {
                second = point;
            }
        });
        clearResizeDrag();
    }

    void clear() {
        change(() -> {
            first = null;
            second = null;
        });
        clearResizeDrag();
    }

    void resize(Side side, int amount) {
        Objects.requireNonNull(side, "side");
        Optional<BlockBox> current = bounds();
        if (amount == 0 || current.isEmpty()) {
            return;
        }
        BlockBox box = current.orElseThrow();
        change(() -> {
            if (first == null) first = new BlockPosition(
                    box.minX(), box.minY(), box.minZ());
            if (second == null) second = new BlockPosition(
                    box.maxX(), box.maxY(), box.maxZ());
            boolean moveFirst = lastResizeSide == side && lastResizeAmount == amount
                    ? lastResizeFirst : firstOn(side);
            if (moveFirst) {
                first = move(first, side, amount);
            } else {
                second = move(second, side, amount);
            }
            lastResizeSide = side;
            lastResizeAmount = amount;
            lastResizeFirst = moveFirst;
        });
    }

    boolean undo() {
        if (undo.isEmpty()) return false;
        redo.addLast(snapshot());
        restore(undo.removeLast());
        return true;
    }

    boolean redo() {
        if (redo.isEmpty()) return false;
        push(undo, snapshot());
        restore(redo.removeLast());
        return true;
    }

    Optional<BlockBox> bounds() {
        if (first == null && second == null) {
            return Optional.empty();
        }
        BlockPosition a = first == null ? second : first;
        BlockPosition b = second == null ? first : second;
        return Optional.of(new BlockBox(
                a.x(), a.y(), a.z(), b.x(), b.y(), b.z()));
    }

    private void extend(BlockPosition point) {
        Optional<BlockBox> current = bounds();
        if (current.isEmpty()) {
            first = point;
            second = point;
            return;
        }
        BlockBox box = current.orElseThrow();
        first = new BlockPosition(
                Math.min(box.minX(), point.x()),
                Math.min(box.minY(), point.y()),
                Math.min(box.minZ(), point.z()));
        second = new BlockPosition(
                Math.max(box.maxX(), point.x()),
                Math.max(box.maxY(), point.y()),
                Math.max(box.maxZ(), point.z()));
    }

    private boolean firstOn(Side side) {
        return switch (side) {
            case MIN_X -> first.x() <= second.x();
            case MAX_X -> first.x() >= second.x();
            case MIN_Y -> first.y() <= second.y();
            case MAX_Y -> first.y() >= second.y();
            case MIN_Z -> first.z() <= second.z();
            case MAX_Z -> first.z() >= second.z();
        };
    }

    private static BlockPosition move(
            BlockPosition point, Side side, int amount) {
        return switch (side) {
            case MIN_X -> new BlockPosition(point.x() - amount, point.y(), point.z());
            case MAX_X -> new BlockPosition(point.x() + amount, point.y(), point.z());
            case MIN_Y -> new BlockPosition(point.x(), point.y() - amount, point.z());
            case MAX_Y -> new BlockPosition(point.x(), point.y() + amount, point.z());
            case MIN_Z -> new BlockPosition(point.x(), point.y(), point.z() - amount);
            case MAX_Z -> new BlockPosition(point.x(), point.y(), point.z() + amount);
        };
    }

    private void change(Runnable mutation) {
        Snapshot before = snapshot();
        mutation.run();
        if (!before.equals(snapshot())) {
            push(undo, before);
            redo.clear();
        }
    }

    private static void push(ArrayDeque<Snapshot> history, Snapshot snapshot) {
        if (history.size() == HISTORY_LIMIT) history.removeFirst();
        history.addLast(snapshot);
    }

    private Snapshot snapshot() {
        return new Snapshot(mode, first, second);
    }

    private void restore(Snapshot snapshot) {
        mode = snapshot.mode;
        first = snapshot.first;
        second = snapshot.second;
        clearResizeDrag();
    }

    private void clearResizeDrag() {
        lastResizeSide = null;
        lastResizeAmount = 0;
        lastResizeFirst = false;
    }

    enum Side {
        MIN_X, MAX_X, MIN_Y, MAX_Y, MIN_Z, MAX_Z
    }

    private record Snapshot(
            SelectionMode mode, BlockPosition first, BlockPosition second) {
    }
}
