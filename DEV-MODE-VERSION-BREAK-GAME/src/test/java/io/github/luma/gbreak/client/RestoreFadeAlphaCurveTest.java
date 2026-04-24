package io.github.luma.gbreak.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RestoreFadeAlphaCurveTest {

    @Test
    void fadesFromOpaqueToTransparent() {
        RestoreFadeAlphaCurve curve = new RestoreFadeAlphaCurve();

        int start = curve.alpha(0);
        int middle = curve.alpha(RestoreFadeAlphaCurve.DEFAULT_DURATION_TICKS / 2);
        int end = curve.alpha(RestoreFadeAlphaCurve.DEFAULT_DURATION_TICKS);

        assertTrue(start > middle);
        assertTrue(middle > end);
        assertEquals(0, end);
    }

    @Test
    void honorsConfiguredDuration() {
        RestoreFadeAlphaCurve curve = new RestoreFadeAlphaCurve();

        assertEquals(0, curve.alpha(40, 40));
        assertTrue(curve.alpha(40, 120) > 0);
    }
}
