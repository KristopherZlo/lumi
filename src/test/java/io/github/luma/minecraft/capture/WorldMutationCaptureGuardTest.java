package io.github.luma.minecraft.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldMutationCaptureGuardTest {

    @Test
    void levelBoundaryFrameClosesAfterException() {
        assertFalse(WorldMutationCaptureGuard.isWithinLevelSetBlockBoundary());

        assertThrows(IllegalStateException.class, () -> {
            try (WorldMutationCaptureGuard.CaptureBoundary ignored =
                         WorldMutationCaptureGuard.pushLevelSetBlockBoundary()) {
                assertTrue(WorldMutationCaptureGuard.isWithinLevelSetBlockBoundary());
                throw new IllegalStateException("boom");
            }
        });

        assertFalse(WorldMutationCaptureGuard.isWithinLevelSetBlockBoundary());
    }

    @Test
    void directSectionSuppressionFrameClosesAfterException() {
        assertFalse(WorldMutationCaptureGuard.suppressesDirectSectionCapture());

        assertThrows(IllegalStateException.class, () -> {
            try (WorldMutationCaptureGuard.CaptureBoundary ignored =
                         WorldMutationCaptureGuard.pushDirectSectionCaptureSuppression()) {
                assertTrue(WorldMutationCaptureGuard.suppressesDirectSectionCapture());
                throw new IllegalStateException("boom");
            }
        });

        assertFalse(WorldMutationCaptureGuard.suppressesDirectSectionCapture());
    }
}
