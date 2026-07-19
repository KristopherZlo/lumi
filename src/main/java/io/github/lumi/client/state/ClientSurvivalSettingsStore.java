package io.github.lumi.client.state;

import io.github.lumi.network.SurvivalSettingsPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Correlates one client-visible Survival settings request at a time. */
public final class ClientSurvivalSettingsStore {
    private UUID pending;
    private Snapshot snapshot;
    private long revision;

    public synchronized void begin(UUID requestId) {
        pending = Objects.requireNonNull(requestId, "requestId");
    }

    public synchronized boolean accept(SurvivalSettingsPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (!payload.requestId().equals(pending)) {
            return false;
        }
        pending = null;
        snapshot = new Snapshot(payload.enabled(), payload.configurable());
        revision++;
        return true;
    }

    public synchronized Optional<Snapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized void clear() {
        pending = null;
        snapshot = null;
        revision++;
    }

    public record Snapshot(boolean enabled, boolean configurable) { }
}
