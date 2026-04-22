package io.github.luma.client.preview;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewFramingCalculatorTest {

    private final PreviewFramingCalculator calculator = new PreviewFramingCalculator();

    @Test
    void keepsWideBoundsInsideTheViewportWithMargin() {
        var framing = this.calculator.calculate(new Bounds3i(
                new BlockPoint(0, 64, 0),
                new BlockPoint(31, 79, 31)
        ));

        assertTrue(framing.scale() > 0.0F);
        assertTrue(Math.abs(framing.offsetX()) < 0.25F);
        assertTrue(Math.abs(framing.offsetY()) < 0.25F);
    }

    @Test
    void raisesResolutionForLargerPreviewVolumes() {
        var small = this.calculator.calculate(new Bounds3i(
                new BlockPoint(0, 64, 0),
                new BlockPoint(7, 71, 7)
        ));
        var large = this.calculator.calculate(new Bounds3i(
                new BlockPoint(0, 32, 0),
                new BlockPoint(63, 127, 63)
        ));

        assertEquals(512, small.resolution());
        assertTrue(large.resolution() > small.resolution());
    }
}
