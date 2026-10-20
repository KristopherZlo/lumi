package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identifies the selected world-history step inside its existing apply journal. */
public record ActionReplayTarget(UUID actor, UUID action, boolean undo, boolean selective) {
    public ActionReplayTarget(UUID actor, UUID action, boolean undo) { this(actor, action, undo, false); }
    public ActionReplayTarget {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
    }
}
