package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationLoadPolicyTest {

    @Test
    void dropsScaleAggressivelyWhenP95TickPressureIsHigh() {
        OperationLoadPolicy policy = new OperationLoadPolicy();
        double scale = 1.0D;

        for (int index = 0; index < 8; index++) {
            scale = policy.nextAdaptiveScale(scale, 0.25D, 1.25D, 2_000_000L, 1_000_000L);
        }

        assertTrue(scale < 0.35D);
        assertTrue(scale >= 0.25D);
    }

    @Test
    void recoversScaleGraduallyWhenPressureStaysLow() {
        OperationLoadPolicy policy = new OperationLoadPolicy();
        double scale = 0.5D;

        for (int index = 0; index < 16; index++) {
            scale = policy.nextAdaptiveScale(scale, 0.25D, 1.25D, 250_000L, 1_000_000L);
        }

        assertTrue(scale > 0.5D);
        assertTrue(scale <= 1.25D);
    }

    @Test
    void clampsInvalidMeasurementsToCurrentBounds() {
        OperationLoadPolicy policy = new OperationLoadPolicy();

        assertEquals(1.0D, policy.nextAdaptiveScale(1.0D, 0.25D, 1.25D, 0L, 1_000_000L));
        assertEquals(0.25D, policy.nextAdaptiveScale(0.1D, 0.25D, 1.25D, 0L, 0L));
    }

    @Test
    void highThroughputBudgetsStillMeasurePressureAgainstResponsiveTickTime() {
        OperationLoadPolicy policy = new OperationLoadPolicy();

        double scale = policy.nextAdaptiveScale(1.0D, 0.25D, 1.25D, 100_000_000L, 200_000_000L);

        assertTrue(scale < 1.0D);
    }
}
