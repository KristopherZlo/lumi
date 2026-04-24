package io.github.luma.gbreak.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CorruptionRestoreCadenceTest {

    @Test
    void spacesRestoreBatchesByConfiguredInterval() {
        CorruptionRestoreCadence cadence = new CorruptionRestoreCadence();

        assertTrue(cadence.shouldRestoreNow(4));
        assertFalse(cadence.shouldRestoreNow(4));
        assertFalse(cadence.shouldRestoreNow(4));
        assertFalse(cadence.shouldRestoreNow(4));
        assertTrue(cadence.shouldRestoreNow(4));
    }

    @Test
    void resetAllowsImmediateNextBatch() {
        CorruptionRestoreCadence cadence = new CorruptionRestoreCadence();

        assertTrue(cadence.shouldRestoreNow(8));
        assertFalse(cadence.shouldRestoreNow(8));

        cadence.reset();

        assertTrue(cadence.shouldRestoreNow(8));
    }
}
