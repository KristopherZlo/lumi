package io.github.luma.minecraft.testing;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureFixtureTimingPlanTest {

    @Test
    void defaultPlanUsesShortToLongWaitWindows() {
        StructureFixtureTimingPlan plan = StructureFixtureTimingPlan.defaultPlan();

        assertEquals(List.of(1, 10, 20, 40), plan.waitTicks());
        assertEquals(1, plan.waitTicks(0));
        assertEquals(40, plan.waitTicks(3));
        assertFalse(plan.exhausted(0));
        assertFalse(plan.exhausted(3));
        assertTrue(plan.exhausted(4));
    }

    @Test
    void rejectsInvalidPlans() {
        assertThrows(NullPointerException.class, () -> newPlan(null));
        assertThrows(IllegalArgumentException.class, () -> newPlan(List.of()));
        assertThrows(IllegalArgumentException.class, () -> newPlan(List.of(1, 0)));
    }

    private static StructureFixtureTimingPlan newPlan(List<Integer> waitTicks) {
        return new StructureFixtureTimingPlan(waitTicks);
    }
}
