package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HighlightPreparationProgressTest {
    @Test
    void hidesIdleAndCompletedWork() {
        assertFalse(HighlightPreparationProgress.NONE.preparing());
        assertEquals(0, HighlightPreparationProgress.NONE.fraction());
        assertFalse(new HighlightPreparationProgress(30, 30).preparing());
    }

    @Test
    void doesNotReportOneHundredPercentBeforeCompletion() {
        var progress = new HighlightPreparationProgress(299, 300);
        assertTrue(progress.preparing());
        assertEquals(99, progress.percent());
        assertEquals(100, new HighlightPreparationProgress(300, 300).percent());
    }

    @Test
    void rejectsInvalidCounts() {
        assertThrows(IllegalArgumentException.class, () -> new HighlightPreparationProgress(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> new HighlightPreparationProgress(6, 5));
    }
}
