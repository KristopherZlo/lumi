package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LumiPageTransitionTest {
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final LumiPageTransition transition =
            new LumiPageTransition(now::get);

    @Test
    void entersFromBelowAndSettles() {
        transition.enter();

        assertFrame(10.0F, 0.0F, transition.frame());
        now.addAndGet(70_000_000L);
        assertFrame(0.3125F, 0.96875F, transition.frame());
        now.addAndGet(70_000_000L);
        assertFrame(0.0F, 1.0F, transition.frame());
        assertFalse(transition.active());
    }

    @Test
    void exitsUpAndCompletesItsDestinationOnce() {
        assertTrue(transition.exit(ProjectTab.ZONES));
        assertFalse(transition.exit(ProjectTab.MORE));

        assertFrame(0.0F, 1.0F, transition.frame());
        now.addAndGet(90_000_000L);
        LumiPageTransition.Frame completed = transition.frame();
        assertFrame(-10.0F, 0.0F, completed);
        assertEquals(ProjectTab.ZONES,
                completed.completedDestination().orElseThrow());
        assertTrue(transition.frame().completedDestination().isEmpty());
    }

    private static void assertFrame(
            float offset, float opacity, LumiPageTransition.Frame frame) {
        assertEquals(offset, frame.offsetY(), 0.00001F);
        assertEquals(opacity, frame.opacity(), 0.00001F);
    }
}
