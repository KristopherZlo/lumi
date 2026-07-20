package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LumiCommitCardTest {
    @Test
    void keepsEveryActionInsideWideAndStackedCards() {
        assertBounded(LumiCommitCard.layout(
                20, 40, 448, 30, true, true, false), false);
        assertBounded(LumiCommitCard.layout(
                20, 40, 128, 54, true, true, true), true);
        assertBounded(LumiCommitCard.layout(
                20, 40, 152, 24, false, false, false), false);
    }

    @Test
    void rejectsUnknownActionSlots() {
        LumiCommitCard.Layout card = LumiCommitCard.layout(
                0, 0, 128, 54, true, true, true);

        var error = assertThrows(IllegalArgumentException.class,
                () -> card.actionX(4));
        assertTrue(error.getMessage().contains("save-card action"));
    }

    private static void assertBounded(
            LumiCommitCard.Layout card, boolean stacked) {
        assertTrue(card.actionX() >= card.x() + 6);
        assertTrue(card.actionsRight() <= card.right() - 6);
        assertTrue(card.actionsBottom() <= card.bottom() - (stacked ? 6 : 0));
        assertTrue(card.textX() >= card.x() + 6);
        int expectedTextRight = stacked
                ? card.right() - 6 : card.actionX() - 4;
        assertEquals(expectedTextRight, card.textX() + card.textWidth());
    }
}
