package io.github.lumi.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only grouped cursor for a builder; it carries no replay payloads. */
public record WorldActionView(long revision, List<Entry> undo, List<Entry> redo) {
    public WorldActionView {
        if (revision < 0) throw new IllegalArgumentException("Invalid action revision");
        undo = List.copyOf(undo);
        redo = List.copyOf(redo);
    }

    /** Undo is oldest first; Redo is next-to-apply first. */
    public record Entry(UUID action, Kind kind, Optional<BlockPosition> origin) {
        public Entry {
            Objects.requireNonNull(action);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(origin);
        }
    }

    public enum Kind { EDIT, RESTORE, BRANCH_SWITCH, WORKSPACE_SWITCH }
}
