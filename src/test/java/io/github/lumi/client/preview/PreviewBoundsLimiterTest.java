package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockBox;
import org.junit.jupiter.api.Test;

class PreviewBoundsLimiterTest {
    private final PreviewBoundsLimiter limiter = new PreviewBoundsLimiter();

    @Test
    void keepsSmallSaveBoundsUnchanged() {
        BlockBox source = new BlockBox(-29, 18, 51, -2, 60, 77);

        assertEquals(source, limiter.limit(source));
    }

    @Test
    void keepsTheTopOfADeepSaveWithinTheSectionBudget() {
        BlockBox limited = limiter.limit(
                new BlockBox(0, 0, 0, 127, 127, 127));

        assertEquals(new BlockBox(0, 64, 0, 127, 127, 127), limited);
        assertTrue(limited.sectionCells(
                PreviewBoundsLimiter.MAX_SECTIONS).size()
                <= PreviewBoundsLimiter.MAX_SECTIONS);
    }

    @Test
    void takesACenteredWindowFromAWideSave() {
        BlockBox limited = limiter.limit(
                new BlockBox(0, 0, 0, 511, 15, 511));

        assertEquals(new BlockBox(128, 0, 128, 383, 15, 383), limited);
        assertEquals(PreviewBoundsLimiter.MAX_SECTIONS,
                limited.sectionCells(PreviewBoundsLimiter.MAX_SECTIONS).size());
    }
}
