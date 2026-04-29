package io.github.luma.client.selection;

public enum LumiRegionSelectionMode {
    CORNERS,
    EXTEND;

    public LumiRegionSelectionMode toggled() {
        return this == CORNERS ? EXTEND : CORNERS;
    }
}
