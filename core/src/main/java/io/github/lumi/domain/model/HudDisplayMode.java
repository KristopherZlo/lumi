package io.github.lumi.domain.model;

/** Persisted presentation choice for workspace status and operation progress. */
public enum HudDisplayMode {
    GUI(1),
    BOSSBAR(0),
    NONE(2);

    private final int id;

    HudDisplayMode(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static HudDisplayMode fromId(int id) {
        for (HudDisplayMode mode : values()) {
            if (mode.id == id) return mode;
        }
        throw new IllegalArgumentException("Unknown HUD display mode: " + id);
    }
}
