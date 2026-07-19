package io.github.lumi.client.state;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BlockPosition;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Project/dimension-scoped wooden-sword selections with a bounded LRU. */
public final class ClientSelection {
    static final int MAX_SCOPES = 32;
    private static final Scope FALLBACK =
            new Scope(new UUID(0, 0), "unscoped");
    private final Map<Scope, SelectionState> states =
            new LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Scope, SelectionState> eldest) {
                    return size() > MAX_SCOPES;
                }
            };
    private Scope active = FALLBACK;

    public synchronized void activate(UUID workspaceId, String dimensionId) {
        active = new Scope(workspaceId, dimensionId);
        state();
    }

    public synchronized void setFirst(BlockPosition position) {
        state().selectPrimary(position);
    }

    public synchronized void setSecond(BlockPosition position) {
        state().selectSecondary(position);
    }

    public synchronized void toggleMode() {
        state().toggleMode();
    }

    public synchronized SelectionMode mode() {
        return state().mode();
    }

    public synchronized void resize(SelectionSide side, int amount) {
        state().resize(side, amount);
    }

    public synchronized void clear() {
        state().clear();
    }

    public synchronized void reset() {
        states.clear();
        active = FALLBACK;
    }

    public synchronized boolean undo() {
        return state().undo();
    }

    public synchronized boolean redo() {
        return state().redo();
    }

    public synchronized Optional<BlockBox> bounds() {
        return state().bounds();
    }

    synchronized int retainedScopes() {
        return states.size();
    }

    private SelectionState state() {
        return states.computeIfAbsent(active, ignored -> new SelectionState());
    }

    private record Scope(UUID workspaceId, String dimensionId) {
        private Scope {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(dimensionId, "dimensionId");
            if (dimensionId.isBlank()) {
                throw new IllegalArgumentException(
                        "Selection dimension cannot be blank");
            }
        }
    }
}
