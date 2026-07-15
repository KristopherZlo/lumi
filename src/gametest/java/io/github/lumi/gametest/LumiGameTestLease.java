package io.github.lumi.gametest;

import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;

/** Serializes tests that exercise Lumi's one-operation-per-dimension contract. */
final class LumiGameTestLease {
    private static UUID activeTest;

    private LumiGameTestLease() { }

    static synchronized void acquire(GameTestHelper helper, UUID test) {
        if (activeTest == null) activeTest = test;
        helper.assertValueEqual(test, activeTest,
                "Another Lumi GameTest owns the dimension");
    }

    static synchronized void release(UUID test) {
        if (!test.equals(activeTest)) {
            throw new IllegalStateException("Lumi GameTest lease changed");
        }
        activeTest = null;
    }
}
