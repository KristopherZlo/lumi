package io.github.lumi.domain.service;

import io.github.lumi.domain.model.EntityState;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Shared endpoint ownership for the journal and unfinished native entity actions. */
public final class LiveEntityStateReferences {
    private final Map<EntityState, Integer> references = new HashMap<>();
    private final Consumer<EntityState> release;

    public LiveEntityStateReferences(Consumer<EntityState> release) {
        this.release = Objects.requireNonNull(release, "release");
    }

    public void retain(Optional<EntityState> state) {
        state.ifPresent(value -> references.merge(value, 1, Integer::sum));
    }

    public void release(Optional<EntityState> state) {
        state.ifPresent(value -> {
            int remaining = references.get(value) - 1;
            if (remaining == 0) {
                references.remove(value);
                release.accept(value);
            } else references.put(value, remaining);
        });
    }

    public void discardUnowned(Optional<EntityState> state) {
        state.filter(value -> !references.containsKey(value)).ifPresent(release);
    }
}
