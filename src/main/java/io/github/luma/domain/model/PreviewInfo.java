package io.github.luma.domain.model;

public record PreviewInfo(
        String fileName,
        int width,
        int height
) {

    public static PreviewInfo none() {
        return new PreviewInfo("", 0, 0);
    }
}
