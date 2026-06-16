package io.github.luma.minecraft.world;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactReplaySafetyTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void trackerQuarantinesPositionAfterRepeatedFailures() {
        WorldApplyExceptionTracker tracker = new WorldApplyExceptionTracker(3, 2);
        BlockPos pos = new BlockPos(1, 64, 1);

        assertFalse(tracker.isQuarantined("guard-tick", pos));
        assertFalse(tracker.recordFailure("guard-tick", pos, new IllegalStateException("first")).quarantined());
        assertFalse(tracker.recordFailure("guard-tick", pos, new IllegalStateException("second")).quarantined());

        WorldApplyExceptionTracker.FailureDecision third =
                tracker.recordFailure("guard-tick", pos, new IllegalStateException("third"));

        assertTrue(third.quarantined());
        assertTrue(tracker.isQuarantined("guard-tick", pos));
        assertEquals(3, tracker.totalFailures());
        assertEquals(1, tracker.quarantinedTargets());
    }

    @Test
    void safeExactReplayStopsCallingDelegateAfterQuarantine() {
        AtomicInteger calls = new AtomicInteger();
        SafeExactReplayStateApplier applier = new SafeExactReplayStateApplier(
                (level, placement, handle, phase) -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("boom");
                },
                new WorldApplyExceptionTracker(3, 2)
        );
        PreparedBlockPlacement placement = placement();

        assertFalse(applier.apply(null, placement, null, "guard-tick").quarantined());
        assertFalse(applier.apply(null, placement, null, "guard-tick").quarantined());
        assertTrue(applier.apply(null, placement, null, "guard-tick").quarantined());
        assertTrue(applier.apply(null, placement, null, "guard-tick").quarantined());

        assertEquals(3, calls.get());
    }

    @Test
    void safeExactReplayClearsFailuresAfterSuccessfulApply() {
        AtomicInteger calls = new AtomicInteger();
        WorldApplyExceptionTracker tracker = new WorldApplyExceptionTracker(3, 2);
        SafeExactReplayStateApplier applier = new SafeExactReplayStateApplier(
                (level, placement, handle, phase) -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("transient");
                    }
                    return true;
                },
                tracker
        );
        PreparedBlockPlacement placement = placement();

        assertFalse(applier.apply(null, placement, null, "guard-tick").applied());
        assertTrue(applier.apply(null, placement, null, "guard-tick").applied());

        assertFalse(tracker.isQuarantined("guard-tick", placement.pos()));
        assertEquals(0, tracker.activeFailureTargets());
    }

    private static PreparedBlockPlacement placement() {
        return new PreparedBlockPlacement(
                new BlockPos(1, 64, 1),
                Blocks.REDSTONE_WIRE.defaultBlockState(),
                null
        );
    }
}
