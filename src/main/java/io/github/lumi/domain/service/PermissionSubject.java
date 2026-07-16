package io.github.lumi.domain.service;

import java.util.Objects;
import java.util.UUID;

/** Server-observed authorization facts for one player and one Lumi request. */
public record PermissionSubject(UUID playerId, boolean operator, boolean survival) {
    public PermissionSubject {
        Objects.requireNonNull(playerId, "playerId");
    }
}
