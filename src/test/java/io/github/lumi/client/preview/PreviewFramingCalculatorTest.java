package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockBox;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class PreviewFramingCalculatorTest {
    private final PreviewFramingCalculator calculator = new PreviewFramingCalculator();

    @Test
    void keepsWideBoundsInsideTheViewportWithMargin() {
        var framing = calculator.calculate(new BlockBox(0, 64, 0, 31, 79, 31));

        assertTrue(framing.scale() > 0.0F);
        assertTrue(Math.abs(framing.offsetX()) < 0.25F);
        assertTrue(Math.abs(framing.offsetY()) < 0.25F);
    }

    @Test
    void raisesAndBoundsResolutionForLargerOrOverflowingVolumes() {
        var small = calculator.calculate(new BlockBox(0, 64, 0, 7, 71, 7));
        var large = calculator.calculate(new BlockBox(0, 32, 0, 63, 127, 63));
        var extreme = calculator.calculate(new BlockBox(
                Integer.MIN_VALUE, 0, Integer.MIN_VALUE,
                Integer.MAX_VALUE, 0, Integer.MAX_VALUE));

        assertEquals(512, small.resolution());
        assertTrue(large.resolution() > small.resolution());
        assertEquals(1536, extreme.resolution());
    }

    @Test
    void keepsXAxisAndZAxisInLegacyIsometricDirection() {
        Vector3f positiveX = new Vector3f(1, 0, 0)
                .mulPosition(PreviewFramingCalculator.rotationMatrix());
        Vector3f positiveZ = new Vector3f(0, 0, 1)
                .mulPosition(PreviewFramingCalculator.rotationMatrix());

        assertTrue(positiveX.x() > 0.0F);
        assertTrue(positiveZ.x() < 0.0F);
        assertEquals(Math.abs(positiveX.x()), Math.abs(positiveZ.x()), 0.0001F);
    }
}
