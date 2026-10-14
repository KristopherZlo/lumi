package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.UUID;

public record CommitAuthor(UUID id, String name) {
    public CommitAuthor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Author name cannot be blank");
        }
    }
}
