package io.github.luma.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WorkZoneState(
        int schemaVersion,
        List<WorkZone> zones,
        Map<String, String> activeZoneByActor
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WorkZoneState {
        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
        zones = zones == null ? List.of() : List.copyOf(zones);
        activeZoneByActor = activeZoneByActor == null ? Map.of() : Map.copyOf(activeZoneByActor);
    }

    public static WorkZoneState empty() {
        return new WorkZoneState(CURRENT_SCHEMA_VERSION, List.of(), Map.of());
    }

    public WorkZoneState withZones(List<WorkZone> zones) {
        return new WorkZoneState(this.schemaVersion, zones, this.activeZoneByActor);
    }

    public WorkZoneState withActiveZone(String actor, String zoneId) {
        String key = normalizeActor(actor);
        LinkedHashMap<String, String> next = new LinkedHashMap<>(this.activeZoneByActor);
        if (zoneId == null || zoneId.isBlank()) {
            next.remove(key);
        } else {
            next.put(key, zoneId);
        }
        return new WorkZoneState(this.schemaVersion, this.zones, next);
    }

    public WorkZoneState withoutZone(String zoneId) {
        String removedZoneId = zoneId == null ? "" : zoneId;
        if (removedZoneId.isBlank()) {
            return this;
        }
        List<WorkZone> nextZones = this.zones.stream()
                .filter(zone -> !removedZoneId.equals(zone.id()))
                .toList();
        LinkedHashMap<String, String> nextActiveZones = new LinkedHashMap<>();
        this.activeZoneByActor.forEach((actor, activeZoneId) -> {
            if (!removedZoneId.equals(activeZoneId)) {
                nextActiveZones.put(actor, activeZoneId);
            }
        });
        return new WorkZoneState(this.schemaVersion, nextZones, nextActiveZones);
    }

    public String activeZoneId(String actor) {
        return this.activeZoneByActor.getOrDefault(normalizeActor(actor), "");
    }

    public static String normalizeActor(String actor) {
        return actor == null || actor.isBlank() ? "player" : actor;
    }
}
