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
}
