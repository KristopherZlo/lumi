package io.github.luma.domain.model;

public enum RestorePlanMode {
    PATCH_REPLAY,
    TARGET_STATE,
    BASELINE_CHUNKS,
    REGEN_TOUCHED_CHUNKS,
    BLOCKED_FINGERPRINT,
    NO_OP
}
