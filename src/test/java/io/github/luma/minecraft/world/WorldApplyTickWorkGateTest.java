package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldApplyTickWorkGateTest {

    private final WorldApplyTickWorkGate gate = new WorldApplyTickWorkGate();
    private final WorldApplyBudget budget = new WorldApplyBudget(512, 1_000_000L, 16, 512, 4, 2, 512, 128);

    @Test
    void allowsRewriteBurstAfterRewriteWorkAlreadyStartedThisTick() {
        assertTrue(this.gate.canStartNextStep(
                true,
                SectionApplyPath.SECTION_REWRITE,
                SectionChangeMask.ENTRY_COUNT,
                1,
                0,
                1,
                0,
                this.budget
        ));
    }

    @Test
    void stopsRewriteWhenRewriteBudgetIsConsumed() {
        assertFalse(this.gate.canStartNextStep(
                true,
                SectionApplyPath.SECTION_REWRITE,
                SectionChangeMask.ENTRY_COUNT * 4,
                4,
                0,
                4,
                0,
                this.budget
        ));
        assertEquals(
                "rewrite-budget-consumed",
                this.gate.decide(
                        true,
                        SectionApplyPath.SECTION_REWRITE,
                        SectionChangeMask.ENTRY_COUNT * 4,
                        4,
                        0,
                        4,
                        0,
                        this.budget
                ).reason()
        );
    }

    @Test
    void keepsRewriteBurstsSeparateFromOtherApplyWork() {
        assertFalse(this.gate.canStartNextStep(
                true,
                SectionApplyPath.SECTION_REWRITE,
                64,
                0,
                64,
                0,
                0,
                this.budget
        ));
        assertEquals(
                "rewrite-after-non-rewrite-work",
                this.gate.decide(
                        true,
                        SectionApplyPath.SECTION_REWRITE,
                        64,
                        0,
                        64,
                        0,
                        0,
                        this.budget
                ).reason()
        );
        assertFalse(this.gate.canStartNextStep(
                true,
                SectionApplyPath.SECTION_NATIVE,
                SectionChangeMask.ENTRY_COUNT,
                1,
                0,
                1,
                0,
                this.budget
        ));
        assertFalse(this.gate.canStartNextStep(
                false,
                null,
                SectionChangeMask.ENTRY_COUNT,
                1,
                0,
                1,
                0,
                this.budget
        ));
    }

    @Test
    void stopsSparseDirectWorkWhenDirectSectionBudgetIsConsumed() {
        assertFalse(this.gate.canStartNextStep(
                false,
                null,
                128,
                0,
                0,
                0,
                2,
                this.budget
        ));
        assertEquals(
                "direct-section-budget-consumed",
                this.gate.decide(
                        false,
                        null,
                        128,
                        0,
                        0,
                        0,
                        2,
                        this.budget
                ).reason()
        );
    }
}
