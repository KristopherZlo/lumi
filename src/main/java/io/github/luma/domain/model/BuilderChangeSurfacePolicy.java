package io.github.luma.domain.model;

import java.util.Collection;
import java.util.List;

/**
 * Defines which stored changes belong on builder-facing surfaces.
 */
public final class BuilderChangeSurfacePolicy {

    public boolean includes(StoredBlockChange change) {
        return change != null && change.visibleInBuilderSurfaces();
    }

    public List<StoredBlockChange> visibleBlockChanges(Collection<StoredBlockChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return List.of();
        }
        return changes.stream()
                .filter(this::includes)
                .toList();
    }

    public int visibleBlockChangeCount(Collection<StoredBlockChange> changes) {
        return this.visibleBlockChanges(changes).size();
    }

    public boolean hasVisibleBlockChanges(Collection<StoredBlockChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return false;
        }
        for (StoredBlockChange change : changes) {
            if (this.includes(change)) {
                return true;
            }
        }
        return false;
    }
}
