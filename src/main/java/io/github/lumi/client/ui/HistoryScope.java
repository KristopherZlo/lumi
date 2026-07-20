package io.github.lumi.client.ui;

import java.util.Objects;
import java.util.UUID;

/** Identifies the immutable history view currently rendered by a page. */
sealed interface HistoryScope {
    record Workspace() implements HistoryScope { }

    record Zone(UUID id) implements HistoryScope {
        public Zone {
            Objects.requireNonNull(id, "id");
        }
    }

    record Dimension(String id) implements HistoryScope {
        public Dimension {
            Objects.requireNonNull(id, "id");
        }
    }
}
