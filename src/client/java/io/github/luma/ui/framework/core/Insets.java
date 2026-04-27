package io.github.luma.ui.framework.core;

public record Insets(int top, int right, int bottom, int left) {

    public static Insets of(int value) {
        int safeValue = Math.max(0, value);
        return new Insets(safeValue, safeValue, safeValue, safeValue);
    }

    public static Insets bottom(int value) {
        return new Insets(0, 0, Math.max(0, value), 0);
    }

    public int horizontal() {
        return this.left + this.right;
    }

    public int vertical() {
        return this.top + this.bottom;
    }
}
