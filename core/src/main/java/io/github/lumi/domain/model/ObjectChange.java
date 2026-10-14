package io.github.lumi.domain.model;

import java.util.Objects;

/** Resolved immutable object identities on the two sides of a comparison. */
public record ObjectChange(ObjectId before, ObjectId after) {
    public ObjectChange {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (before.equals(after)) {
            throw new IllegalArgumentException("Object change must contain distinct IDs");
        }
    }
}
