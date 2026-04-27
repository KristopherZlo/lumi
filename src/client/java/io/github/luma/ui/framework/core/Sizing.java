package io.github.luma.ui.framework.core;

public final class Sizing {

    public enum Mode {
        FIXED,
        FILL,
        EXPAND,
        CONTENT
    }

    private final Mode mode;
    private final int value;
    private final int contentPadding;

    private Sizing(Mode mode, int value, int contentPadding) {
        this.mode = mode;
        this.value = value;
        this.contentPadding = contentPadding;
    }

    public static Sizing fixed(int value) {
        return new Sizing(Mode.FIXED, Math.max(0, value), 0);
    }

    public static Sizing fill(int percent) {
        return new Sizing(Mode.FILL, Math.max(0, percent), 0);
    }

    public static Sizing expand(int weight) {
        return new Sizing(Mode.EXPAND, Math.max(1, weight), 0);
    }

    public static Sizing content() {
        return content(0);
    }

    public static Sizing content(int padding) {
        return new Sizing(Mode.CONTENT, 0, Math.max(0, padding));
    }

    public Mode mode() {
        return this.mode;
    }

    public int value() {
        return this.value;
    }

    public int contentPadding() {
        return this.contentPadding;
    }

    public boolean flexible() {
        return this.mode == Mode.FILL || this.mode == Mode.EXPAND;
    }

    public int resolve(int available, int contentSize) {
        int safeAvailable = Math.max(0, available);
        return switch (this.mode) {
            case FIXED -> this.value;
            case FILL -> Math.max(0, safeAvailable * this.value / 100);
            case EXPAND -> safeAvailable;
            case CONTENT -> Math.max(0, contentSize + this.contentPadding * 2);
        };
    }
}
