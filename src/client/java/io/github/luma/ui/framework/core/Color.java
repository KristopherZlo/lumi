package io.github.luma.ui.framework.core;

public record Color(int argb) {

    public static Color ofRgb(int rgb) {
        return new Color(0xFF000000 | (rgb & 0x00FFFFFF));
    }
}
