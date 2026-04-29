package io.github.luma.domain.model;

public enum PartialRestoreMode {
    SELECTED_AREA,
    OUTSIDE_SELECTED_AREA;

    public boolean includes(boolean insideBounds) {
        return this == SELECTED_AREA ? insideBounds : !insideBounds;
    }
}
