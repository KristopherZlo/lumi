package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LegacyModalLayoutTest {
    @Test
    void centersAndClampsTheLegacySaveDialog() {
        LegacyModalLayout wide = LegacyModalLayout.fit(1280, 720, 194);
        assertEquals(320, wide.width());
        assertEquals(480, wide.x());
        assertEquals(263, wide.y());

        LegacyModalLayout narrow = LegacyModalLayout.fit(300, 180, 194);
        assertEquals(280, narrow.width());
        assertEquals(10, narrow.x());
        assertEquals(10, narrow.y());
        assertEquals(160, narrow.height());
    }
}
