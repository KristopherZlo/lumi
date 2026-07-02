package io.github.luma.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

public record PlayerRespawnState(
        int schemaVersion,
        Map<String, List<PlayerRespawnPoint>> versions
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public PlayerRespawnState {
        versions = versions == null ? Map.of() : copy(versions);
    }

    public static PlayerRespawnState empty() {
        return new PlayerRespawnState(CURRENT_SCHEMA_VERSION, Map.of());
    }

    public List<PlayerRespawnPoint> pointsFor(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return List.of();
        }
        return this.versions.getOrDefault(versionId, List.of());
    }

    public PlayerRespawnState withVersion(String versionId, List<PlayerRespawnPoint> points) {
        if (versionId == null || versionId.isBlank()) {
            return this;
        }
        Map<String, List<PlayerRespawnPoint>> next = new LinkedHashMap<>(this.versions);
        if (points == null || points.isEmpty()) {
            next.remove(versionId);
        } else {
            next.put(versionId, List.copyOf(points));
        }
        return new PlayerRespawnState(CURRENT_SCHEMA_VERSION, next);
    }

    private static Map<String, List<PlayerRespawnPoint>> copy(Map<String, List<PlayerRespawnPoint>> versions) {
        Map<String, List<PlayerRespawnPoint>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<PlayerRespawnPoint>> entry : versions.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()) {
                copy.put(entry.getKey(), entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
