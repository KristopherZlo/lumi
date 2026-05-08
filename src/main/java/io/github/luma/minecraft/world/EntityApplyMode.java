package io.github.luma.minecraft.world;

/**
 * Controls whether prepared entity changes are applied as a delta or as the
 * authoritative placed-entity state for a chunk.
 */
public enum EntityApplyMode {
    DELTA,
    REPLACE_PLACED_IN_CHUNK
}
