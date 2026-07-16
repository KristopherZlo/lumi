package io.github.lumi.client.state;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BlockPosition;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/** Session-only two-corner selection with bounded local undo/redo. */
public final class ClientSelection {
    private static final int MAX_HISTORY = 64;
    private final ArrayDeque<State> undo = new ArrayDeque<>();
    private final ArrayDeque<State> redo = new ArrayDeque<>();
    private State current = new State(Optional.empty(), Optional.empty());

    public void setFirst(BlockPosition position) {
        update(new State(Optional.of(Objects.requireNonNull(position, "position")), current.second));
    }

    public void setSecond(BlockPosition position) {
        update(new State(current.first, Optional.of(Objects.requireNonNull(position, "position"))));
    }

    public void clear() {
        update(new State(Optional.empty(), Optional.empty()));
    }

    public void reset() {
        current = new State(Optional.empty(), Optional.empty());
        undo.clear();
        redo.clear();
    }

    public boolean undo() {
        if (undo.isEmpty()) {
            return false;
        }
        redo.addLast(current);
        current = undo.removeLast();
        return true;
    }

    public boolean redo() {
        if (redo.isEmpty()) {
            return false;
        }
        push(undo, current);
        current = redo.removeLast();
        return true;
    }

    public Optional<BlockBox> bounds() {
        if (current.first.isEmpty() || current.second.isEmpty()) {
            return Optional.empty();
        }
        BlockPosition first = current.first.orElseThrow();
        BlockPosition second = current.second.orElseThrow();
        return Optional.of(new BlockBox(
                first.x(), first.y(), first.z(),
                second.x(), second.y(), second.z()));
    }

    private void update(State next) {
        if (current.equals(next)) {
            return;
        }
        push(undo, current);
        redo.clear();
        current = next;
    }

    private static void push(ArrayDeque<State> history, State state) {
        if (history.size() == MAX_HISTORY) {
            history.removeFirst();
        }
        history.addLast(state);
    }

    private record State(
            Optional<BlockPosition> first,
            Optional<BlockPosition> second) {
        private State {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
        }
    }
}
