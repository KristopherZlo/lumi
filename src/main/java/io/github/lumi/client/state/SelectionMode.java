package io.github.lumi.client.state;

/** Wooden-sword click behavior for Lumi selections. */
public enum SelectionMode {
    CORNERS,
    EXTEND;

    public SelectionMode toggled() {
        return this == CORNERS ? EXTEND : CORNERS;
    }
}
