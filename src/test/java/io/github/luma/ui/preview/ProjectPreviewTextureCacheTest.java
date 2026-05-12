package io.github.luma.ui.preview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectPreviewTextureCacheTest {

    @Test
    void estimatesTextureMemoryFromRgbaPixels() {
        assertEquals(4L, ProjectPreviewTextureCache.estimatedTextureBytesForTest(0, 0));
        assertEquals(1_048_576L, ProjectPreviewTextureCache.estimatedTextureBytesForTest(512, 512));
    }
}
