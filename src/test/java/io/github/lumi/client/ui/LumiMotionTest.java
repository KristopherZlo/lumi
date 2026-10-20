package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LumiMotionTest {
    @Test
    void usesClampedEaseOutQuintProgress() {
        assertEquals(0.0F, LumiMotion.easeOutQuint(-1.0F));
        assertEquals(0.96875F, LumiMotion.easeOutQuint(0.5F));
        assertEquals(1.0F, LumiMotion.easeOutQuint(2.0F));
    }

    @Test
    void advancesFromARealTimeSource() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        LumiMotion motion = new LumiMotion(now::get);

        assertEquals(1.0F, motion.value());
        assertFalse(motion.running());

        motion.start(100);
        assertEquals(0.0F, motion.value());
        assertTrue(motion.running());

        now.addAndGet(50_000_000L);
        assertEquals(0.96875F, motion.value());

        now.addAndGet(50_000_000L);
        assertEquals(1.0F, motion.value());
        assertFalse(motion.running());
    }

    @Test
    void retargetsHoverWithoutJumpingAndCanDisableMotion() {
        AtomicLong now = new AtomicLong();
        LumiMotion motion = new LumiMotion(now::get);
        assertEquals(0, motion.toward(1));
        now.set(150_000_000L);
        float hovered = motion.toward(1);
        assertTrue(hovered > 0 && hovered < 1);
        assertEquals(hovered, motion.toward(0));
        now.set(450_000_000L);
        assertEquals(0, motion.toward(0));
        try {
            LumiMotion.setEnabled(false);
            assertEquals(1, motion.toward(1));
            assertEquals(0, motion.toward(0));
            assertFalse(motion.running());
        } finally {
            LumiMotion.setEnabled(true);
        }
        assertEquals(0, motion.toward(0));
    }
}
