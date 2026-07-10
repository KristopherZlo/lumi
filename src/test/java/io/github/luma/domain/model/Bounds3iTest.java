package io.github.luma.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Bounds3iTest {

    @Test
    void extremeCoordinatesDoNotOverflowVolume() {
        Bounds3i bounds = new Bounds3i(
                new BlockPoint(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                new BlockPoint(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
        );

        assertEquals(Long.MAX_VALUE, bounds.volume());
        assertThrows(ArithmeticException.class, bounds::sizeX);
    }

    @Test
    void reversedBoundsAreNormalized() {
        Bounds3i bounds = new Bounds3i(
                new BlockPoint(1, 0, 0),
                new BlockPoint(0, 0, 0)
        );

        assertEquals(new BlockPoint(0, 0, 0), bounds.min());
        assertEquals(new BlockPoint(1, 0, 0), bounds.max());
    }
}
