package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.service.LiveActionJournal;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Groups only explicitly announced native dispatches on their original connection. */
public final class AxiomBufferActionGrouper {
    public static final int MAX_PENDING = 64;
    private static final String UNAVAILABLE = "Axiom dispatch is incomplete or exceeded its live history limit";
    private final LiveActionJournal journal;
    private final Map<Key, Pending> pending = new HashMap<>();
    private final Set<Object> blocked = Collections.newSetFromMap(new IdentityHashMap<>());

    public AxiomBufferActionGrouper(LiveActionJournal journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    public void begin(Object connection, UUID actor, UUID dispatch) {
        Key key = new Key(connection, dispatch);
        if (blocked.contains(connection) || pending.containsKey(key)) return;
        if (pending.size() >= MAX_PENDING) {
            blocked.add(connection);
            return;
        }
        pending.put(key, new Pending(Objects.requireNonNull(actor, "actor")));
    }

    public void attach(Object connection, UUID actor, UUID dispatch, UUID action) {
        if (!journal.owner(action).filter(actor::equals).isPresent()) {
            throw new IllegalArgumentException("Axiom action belongs to another builder");
        }
        Pending edit = pending.get(new Key(connection, dispatch));
        if (blocked.contains(connection) || edit == null) {
            journal.makeUnavailable(action, UNAVAILABLE);
        } else if (!edit.actor.equals(actor)) {
            throw new IllegalArgumentException("Axiom dispatch belongs to another builder");
        } else if (edit.root == null) {
            edit.root = action;
            journal.holdForMoreChanges(action);
        } else if (!journal.isAvailable(edit.root)) {
            journal.makeUnavailable(action, UNAVAILABLE);
        } else {
            journal.mergeGroups(edit.root, action);
        }
    }

    public void complete(Object connection, UUID dispatch) {
        finish(pending.remove(new Key(connection, dispatch)));
    }

    public void finishConnection(Object connection) {
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().connection != connection) continue;
            finish(entry.getValue());
            iterator.remove();
        }
        blocked.remove(connection);
    }

    public void clear() {
        pending.values().forEach(this::finish);
        pending.clear();
        blocked.clear();
    }

    int pendingCount() { return pending.size(); }

    private void finish(Pending edit) {
        if (edit != null && edit.root != null) journal.finishChanges(edit.root);
    }

    private static final class Pending {
        private final UUID actor;
        private UUID root;
        private Pending(UUID actor) { this.actor = actor; }
    }

    private record Key(Object connection, UUID dispatch) {
        private Key {
            Objects.requireNonNull(connection, "connection");
            Objects.requireNonNull(dispatch, "dispatch");
        }
        @Override public boolean equals(Object value) {
            return value instanceof Key other && connection == other.connection && dispatch.equals(other.dispatch);
        }
        @Override public int hashCode() { return 31 * System.identityHashCode(connection) + dispatch.hashCode(); }
    }
}
