package io.github.lumi.client.state;

import io.github.lumi.domain.model.ZoneShellFace;
import io.github.lumi.network.ZoneOverlayPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Correlates ordered shell batches and publishes only complete snapshots. */
public final class ClientZoneOverlayStore {
    private Pending pending;
    private final LinkedHashMap<UUID, MutableZone> assembling =
            new LinkedHashMap<>();
    private Snapshot published;
    private int nextBatch;

    public synchronized void begin(
            UUID requestId, String dimensionId, UUID workspaceId) {
        pending = new Pending(requestId, dimensionId, workspaceId);
        assembling.clear();
        published = null;
        nextBatch = 0;
    }

    public synchronized boolean accept(ZoneOverlayPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (pending == null || !pending.matches(payload)
                || payload.batchIndex() != nextBatch) {
            return false;
        }
        nextBatch++;
        payload.zone().ifPresent(this::append);
        if (payload.complete()) {
            List<ZoneView> zones = payload.error().isEmpty()
                    ? assembling.values().stream()
                            .map(MutableZone::freeze).toList()
                    : List.of();
            published = new Snapshot(
                    payload.dimensionId(), payload.workspaceId(),
                    zones, payload.error());
        }
        return true;
    }

    public synchronized Optional<Snapshot> snapshot() {
        return Optional.ofNullable(published);
    }

    public synchronized void clear() {
        pending = null;
        assembling.clear();
        published = null;
        nextBatch = 0;
    }

    private void append(ZoneOverlayPayload.ZoneBatch batch) {
        MutableZone zone = assembling.computeIfAbsent(
                batch.id(), ignored -> new MutableZone(batch));
        zone.append(batch);
    }

    public record Snapshot(
            String dimensionId,
            UUID workspaceId,
            List<ZoneView> zones,
            String error) {
        public Snapshot {
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(workspaceId, "workspaceId");
            zones = List.copyOf(Objects.requireNonNull(zones, "zones"));
            Objects.requireNonNull(error, "error");
        }
    }

    public record ZoneView(
            UUID id,
            String name,
            int color,
            long revision,
            boolean active,
            List<ZoneShellFace> faces) {
        public ZoneView {
            faces = List.copyOf(faces);
        }
    }

    private record Pending(
            UUID requestId, String dimensionId, UUID workspaceId) {
        private boolean matches(ZoneOverlayPayload payload) {
            return requestId.equals(payload.requestId())
                    && dimensionId.equals(payload.dimensionId())
                    && workspaceId.equals(payload.workspaceId());
        }
    }

    private static final class MutableZone {
        private final UUID id;
        private final String name;
        private final int color;
        private final long revision;
        private final boolean active;
        private final List<ZoneShellFace> faces = new ArrayList<>();

        private MutableZone(ZoneOverlayPayload.ZoneBatch first) {
            id = first.id();
            name = first.name();
            color = first.color();
            revision = first.revision();
            active = first.active();
        }

        private void append(ZoneOverlayPayload.ZoneBatch batch) {
            if (!id.equals(batch.id()) || !name.equals(batch.name())
                    || color != batch.color() || revision != batch.revision()
                    || active != batch.active()) {
                throw new IllegalArgumentException(
                        "Zone metadata changed between overlay batches");
            }
            faces.addAll(batch.faces());
        }

        private ZoneView freeze() {
            return new ZoneView(id, name, color, revision, active, faces);
        }
    }
}
