package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LumiPreviewRendererTest {
    @Test
    void advancesThroughAllEightFrames() {
        for (int frame = 0; frame < 8; frame++) {
            assertEquals(frame, LumiPreviewRenderer.frame(frame * 100L));
        }
        assertEquals(0, LumiPreviewRenderer.frame(800L));
    }
}
