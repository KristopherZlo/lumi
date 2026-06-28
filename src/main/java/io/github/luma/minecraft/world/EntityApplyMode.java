package io.github.luma.minecraft.world;

/**
 * Controls whether prepared entity changes are applied as a delta or as the
 * authoritative entity state for a chunk.
 */
public enum EntityApplyMode {
    DELTA,
    REPLACE_ENTITIES_IN_CHUNK
}
