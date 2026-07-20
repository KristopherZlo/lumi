package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LumiModalLayoutTest {
    @Test
    void centersAndClampsTheV2SaveDialog() {
        LumiModalLayout wide = LumiModalLayout.fit(1280, 720, 194);
        assertEquals(320, wide.width());
        assertEquals(480, wide.x());
        assertEquals(263, wide.y());

        LumiModalLayout narrow = LumiModalLayout.fit(300, 180, 194);
        assertEquals(280, narrow.width());
        assertEquals(10, narrow.x());
        assertEquals(10, narrow.y());
        assertEquals(160, narrow.height());
    }

    @Test
    void usesTheWholeViewportWhenMarginsDoNotFit() {
        LumiModalLayout layout = LumiModalLayout.fit(12, 12, 260);

        assertEquals(6, layout.x());
        assertEquals(6, layout.y());
        assertEquals(0, layout.width());
        assertEquals(0, layout.height());
    }
}
