package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.model.WorkZoneState;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.WorkZoneRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class WorkZoneService {

    private static final Map<Path, WorkZoneState> STATE_CACHE = new ConcurrentHashMap<>();
    // ponytail: global lock; switch to per-project locks if zone writes become contended.
    private static final Object STATE_LOCK = new Object();

    private static final int[] MINECRAFT_DYE_COLORS = {
            0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA,
            0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
            0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA,
            0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21
    };

    private final WorkZoneRepository repository;

    public WorkZoneService() {
        this(new WorkZoneRepository());
    }

    public WorkZoneService(WorkZoneRepository repository) {
        this.repository = repository;
    }

    public WorkZoneState load(ProjectLayout layout) throws IOException {
        synchronized (STATE_LOCK) {
            return this.loadCached(layout);
        }
    }

    public WorkZone createZone(ProjectLayout layout, String projectId, String name, String actor, Instant now) throws IOException {
        String zoneName = name == null ? "" : name.trim();
        if (zoneName.isBlank()) {
            throw new IllegalArgumentException("Zone name is required");
        }
        synchronized (STATE_LOCK) {
            WorkZoneState state = this.loadCached(layout);
            WorkZone zone = new WorkZone(
                    UUID.randomUUID().toString(),
                    projectId,
                    zoneName,
                    this.nextColor(state.zones()),
                    List.of(),
                    WorkZoneState.normalizeActor(actor),
                    now,
                    now
            );
            List<WorkZone> zones = new ArrayList<>(state.zones());
            zones.add(zone);
            this.save(layout, state.withZones(zones).withActiveZone(actor, zone.id()));
            return zone;
        }
    }

    public WorkZoneState selectZone(ProjectLayout layout, String actor, String zoneId) throws IOException {
        synchronized (STATE_LOCK) {
            WorkZoneState state = this.loadCached(layout);
            if (zoneId != null && !zoneId.isBlank() && state.zones().stream().noneMatch(zone -> zone.id().equals(zoneId))) {
                throw new IllegalArgumentException("Unknown zone");
            }
            WorkZoneState next = state.withActiveZone(actor, zoneId);
            this.save(layout, next);
            return next;
        }
    }

    public Optional<WorkZone> activeZone(ProjectLayout layout, String actor) throws IOException {
        synchronized (STATE_LOCK) {
            WorkZoneState state = this.loadCached(layout);
            String activeZoneId = state.activeZoneId(actor);
            return state.zones().stream().filter(zone -> zone.id().equals(activeZoneId)).findFirst();
        }
    }

    public Optional<WorkZone> touchBlock(ProjectLayout layout, String actor, BlockPoint point, Instant now) throws IOException {
        if (point == null) {
            return Optional.empty();
        }
        return this.touchCell(layout, actor, WorkZoneCell.from(point), now);
    }

    public Optional<WorkZone> touchCell(ProjectLayout layout, String actor, WorkZoneCell cell, Instant now) throws IOException {
        return this.addCells(layout, actor, cell == null ? List.of() : List.of(cell), now);
    }

    public Optional<WorkZone> addCells(ProjectLayout layout, String actor, Collection<WorkZoneCell> cells, Instant now) throws IOException {
        return this.updateCells(layout, actor, cells, now, true);
    }

    public Optional<WorkZone> removeCells(ProjectLayout layout, String actor, Collection<WorkZoneCell> cells, Instant now) throws IOException {
        return this.updateCells(layout, actor, cells, now, false);
    }

    private Optional<WorkZone> updateCells(
            ProjectLayout layout,
            String actor,
            Collection<WorkZoneCell> cells,
            Instant now,
            boolean add
    ) throws IOException {
        synchronized (STATE_LOCK) {
            WorkZoneState state = this.loadCached(layout);
            String activeZoneId = state.activeZoneId(actor);
            if (activeZoneId.isBlank() || cells == null || cells.isEmpty()) {
                return Optional.empty();
            }

            List<WorkZone> zones = new ArrayList<>(state.zones());
            for (int index = 0; index < zones.size(); index++) {
                WorkZone zone = zones.get(index);
                if (!zone.id().equals(activeZoneId)) {
                    continue;
                }
                WorkZone next = add ? zone.withCells(cells, now) : zone.withoutCells(cells, now);
                if (next != zone) {
                    zones.set(index, next);
                    this.save(layout, state.withZones(zones));
                }
                return Optional.of(next);
            }
            return Optional.empty();
        }
    }

    private WorkZoneState loadCached(ProjectLayout layout) throws IOException {
        Path key = cacheKey(layout);
        WorkZoneState cached = STATE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        WorkZoneState loaded = this.repository.load(layout);
        WorkZoneState existing = STATE_CACHE.putIfAbsent(key, loaded);
        return existing == null ? loaded : existing;
    }

    private void save(ProjectLayout layout, WorkZoneState state) throws IOException {
        this.repository.save(layout, state);
        STATE_CACHE.put(cacheKey(layout), state);
    }

    private static Path cacheKey(ProjectLayout layout) {
        return layout.root().toAbsolutePath().normalize();
    }

    private int nextColor(List<WorkZone> zones) {
        Set<Integer> used = new HashSet<>();
        for (WorkZone zone : zones == null ? List.<WorkZone>of() : zones) {
            used.add(zone.color());
        }
        for (int color : MINECRAFT_DYE_COLORS) {
            if (!used.contains(color)) {
                return color;
            }
        }
        for (int attempt = 0; attempt < 64; attempt++) {
            int color = ThreadLocalRandom.current().nextInt(0x1000000);
            if (!used.contains(color)) {
                return color;
            }
        }
        for (int color = 0; color <= 0xFFFFFF; color++) {
            if (!used.contains(color)) {
                return color;
            }
        }
        throw new IllegalStateException("No zone colors are available");
    }
}
