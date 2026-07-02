package io.github.luma.domain.model;

public record PlayerRespawnPoint(
        String playerUuid,
        String playerName,
        String dimensionId,
        int x,
        int y,
        int z,
        float yaw,
        float pitch,
        boolean forced
) {
}
