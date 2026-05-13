package io.github.luma.minecraft.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class CaptureDiagnosticsLoggerTest {

    private final CaptureDiagnosticsLogger logger = new CaptureDiagnosticsLogger();

    @Test
    void formatsPositionsForStableLogFields() {
        assertEquals("unknown", this.logger.formatPos(null));
        assertEquals("1,2,3", this.logger.formatPos(new BlockPos(1, 2, 3)));
    }

    @Test
    void logsBufferProgressOnlyAtCaptureMilestones() {
        assertTrue(this.logger.shouldLogBufferProgress(1));
        assertTrue(this.logger.shouldLogBufferProgress(64));
        assertTrue(this.logger.shouldLogBufferProgress(256));
        assertTrue(this.logger.shouldLogBufferProgress(1024));
        assertTrue(this.logger.shouldLogBufferProgress(2048));

        assertFalse(this.logger.shouldLogBufferProgress(2));
        assertFalse(this.logger.shouldLogBufferProgress(512));
        assertFalse(this.logger.shouldLogBufferProgress(1023));
    }
}
