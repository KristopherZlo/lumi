package io.github.luma.minecraft.world;

enum ApplyWorkKind {
    SPARSE_DIRECT,
    SECTION_NATIVE,
    SECTION_REWRITE,
    BLOCK_ENTITY,
    ENTITY,
    PRELOAD_SYNC,
    REDSTONE_DRAIN,
    LIGHT_DRAIN,
    UNKNOWN
}
