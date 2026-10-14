package io.github.lumi.domain.model;

/** One player's persistent respawn point in this dimension. */
public record PlayerSpawn(int x, int y, int z, float yaw, float pitch, boolean forced) {
    public PlayerSpawn {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Player spawn rotation must be finite");
        }
    }
}
