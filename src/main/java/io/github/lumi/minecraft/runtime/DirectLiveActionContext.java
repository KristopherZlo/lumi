package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.service.LiveActionJournal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-thread root action token shared by nested Minecraft calls. */
public final class DirectLiveActionContext {
    private static final ThreadLocal<Active> CURRENT = new ThreadLocal<>();

    private DirectLiveActionContext() { }

    public static Scope open(LiveActionJournal journal, UUID player) {
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(player, "player");
        Active active = CURRENT.get();
        if (active == null) {
            active = new Active(journal, player, journal.begin(player));
            CURRENT.set(active);
        } else if (active.journal != journal || !active.player.equals(player)) {
            throw new IllegalStateException("Cannot nest different live action roots on one thread");
        }
        long scopeId = ++active.nextScopeId;
        active.scopes.addLast(scopeId);
        return new Scope(active, scopeId);
    }

    public static Optional<UUID> current(LiveActionJournal journal) {
        Active active = CURRENT.get();
        return active != null && active.journal == journal
                ? Optional.of(active.action) : Optional.empty();
    }

    public static final class Scope implements AutoCloseable {
        private final Active active;
        private final long id;
        private boolean closed;

        private Scope(Active active, long id) {
            this.active = active;
            this.id = id;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            Active current = CURRENT.get();
            if (current != active || !Long.valueOf(id).equals(active.scopes.peekLast())) {
                throw new IllegalStateException("Live action scopes must close in reverse order");
            }
            closed = true;
            active.scopes.removeLast();
            if (active.scopes.isEmpty()) {
                try {
                    active.journal.close(active.action);
                } finally {
                    CURRENT.remove();
                }
            }
        }
    }

    private static final class Active {
        private final LiveActionJournal journal;
        private final UUID player;
        private final UUID action;
        private final Deque<Long> scopes = new ArrayDeque<>();
        private long nextScopeId;

        private Active(LiveActionJournal journal, UUID player, UUID action) {
            this.journal = journal;
            this.player = player;
            this.action = action;
        }
    }
}
