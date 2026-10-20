package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Native gesture identity within one retained Lumi history. */
public record AxiomEntryIdentity(UUID epoch, UUID entry) {
    public AxiomEntryIdentity { Objects.requireNonNull(epoch); Objects.requireNonNull(entry); }
}
