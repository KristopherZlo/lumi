package io.github.luma.minecraft.world;

enum BlockCommitFallbackReason {
    NONE("none"),
    NULL_LEVEL("null-level"),
    EMPTY_BATCH("empty-batch"),
    MIXED_CHUNK("mixed-chunk"),
    MIXED_SECTION("mixed-section"),
    REWRITE_REJECTED("rewrite-rejected"),
    REWRITE_UNAVAILABLE("rewrite-unavailable"),
    REWRITE_BLOCK_ENTITY("rewrite-block-entity"),
    REWRITE_POI("rewrite-poi"),
    NATIVE_REJECTED("native-rejected"),
    CHUNK_NOT_LOADED("chunk-not-loaded"),
    SECTION_OUT_OF_RANGE("section-out-of-range"),
    SECTION_MISSING("section-missing");

    private final String label;

    BlockCommitFallbackReason(String label) {
        this.label = label;
    }

    String label() {
        return this.label;
    }
}
