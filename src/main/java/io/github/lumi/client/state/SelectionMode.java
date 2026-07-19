package io.github.lumi.client.state;

/** Wooden-sword click behavior retained from legacy Lumi. */
public enum SelectionMode {
    CORNERS,
    EXTEND;

    public SelectionMode toggled() {
        return this == CORNERS ? EXTEND : CORNERS;
    }
}
