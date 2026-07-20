package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LumiScrollbarTest {
    @Test
    void thumbTracksAndClampsTheScrollableExtent() {
        assertEquals(25, LumiScrollbar.thumbHeight(100, 20, 5));
        assertEquals(10, LumiScrollbar.thumbHeight(100, 100, 1));
        assertEquals(0, LumiScrollbar.offsetAt(100, 20, 5, -10, 0));
        assertEquals(15, LumiScrollbar.offsetAt(100, 20, 5, 100, 0));
        assertEquals(8, LumiScrollbar.offsetAt(100, 20, 5, 50, 10));
    }
}
