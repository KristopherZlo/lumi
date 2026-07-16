package io.github.lumi.domain.service;

import java.io.IOException;
import java.util.UUID;

/** Narrow persistence port for the world-owned Survival permission choice. */
public interface SurvivalOptInStore {
    boolean isEnabled(UUID playerId) throws IOException;

    void setEnabled(UUID playerId, boolean enabled) throws IOException;
}
