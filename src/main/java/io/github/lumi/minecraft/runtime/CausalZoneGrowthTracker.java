package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.ZoneService;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Batches causal zone-cell growth before persisting it off the server thread. */
public final class CausalZoneGrowthTracker {
    private final ZoneService zones;
    private final Executor background;
    private final Consumer<Throwable> failureObserver;
    private final Map<ActorScope, Set<SectionKey>> pending = new HashMap<>();
    private boolean writeRunning;
    private boolean failureReported;

    public CausalZoneGrowthTracker(
            ZoneService zones, Executor background, Consumer<Throwable> failureObserver) {
        this.zones = Objects.requireNonNull(zones, "zones");
        this.background = Objects.requireNonNull(background, "background");
        this.failureObserver = Objects.requireNonNull(failureObserver, "failureObserver");
    }

    public synchronized void record(UUID workspaceId, UUID actor, SectionKey cell) {
        var scope = new ActorScope(
                Objects.requireNonNull(workspaceId, "workspaceId"),
                Objects.requireNonNull(actor, "actor"));
        pending.computeIfAbsent(scope, ignored -> new HashSet<>())
                .add(Objects.requireNonNull(cell, "cell"));
    }

    public void flush() {
        Map<ActorScope, Set<SectionKey>> batch;
        synchronized (this) {
            if (writeRunning || pending.isEmpty()) {
                return;
            }
            batch = snapshotAndClear();
            writeRunning = true;
        }
        try {
            background.execute(() -> persist(batch));
        } catch (RuntimeException rejected) {
            fail(batch, rejected);
        }
    }

    private void persist(Map<ActorScope, Set<SectionKey>> batch) {
        try {
            for (var entry : batch.entrySet()) {
                zones.growActiveForActor(
                        entry.getKey().workspaceId(), entry.getKey().actor(), entry.getValue());
            }
            boolean more;
            synchronized (this) {
                writeRunning = false;
                failureReported = false;
                more = !pending.isEmpty();
            }
            if (more) {
                flush();
            }
        } catch (IOException | RuntimeException failed) {
            fail(batch, failed);
        }
    }

    private void fail(Map<ActorScope, Set<SectionKey>> batch, Throwable failure) {
        boolean report;
        synchronized (this) {
            batch.forEach((scope, cells) -> pending
                    .computeIfAbsent(scope, ignored -> new HashSet<>()).addAll(cells));
            writeRunning = false;
            report = !failureReported;
            failureReported = true;
        }
        if (report) {
            failureObserver.accept(failure);
        }
    }

    private Map<ActorScope, Set<SectionKey>> snapshotAndClear() {
        var snapshot = new HashMap<ActorScope, Set<SectionKey>>();
        pending.forEach((scope, cells) -> snapshot.put(scope, Set.copyOf(cells)));
        pending.clear();
        return Map.copyOf(snapshot);
    }

    private record ActorScope(UUID workspaceId, UUID actor) { }
}
