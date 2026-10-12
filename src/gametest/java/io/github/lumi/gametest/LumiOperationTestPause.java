package io.github.lumi.gametest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Test-only phase rendezvous. Worker waits are bounded; server ticks never block. */
public final class LumiOperationTestPause implements AutoCloseable {
    private static volatile LumiOperationTestPause save;
    private static volatile LumiOperationTestPause restore;
    private final BooleanSupplier restoreBoundary;
    private final CountDownLatch release = new CountDownLatch(1);
    private final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
    private volatile boolean reached;

    private LumiOperationTestPause(BooleanSupplier restoreBoundary) {
        this.restoreBoundary = restoreBoundary;
    }

    public static LumiOperationTestPause savePublication() {
        if (save != null) throw new IllegalStateException("Save pause already armed");
        return save = new LumiOperationTestPause(null);
    }

    public static LumiOperationTestPause restoreAfterMutation(BooleanSupplier boundary) {
        if (restore != null) throw new IllegalStateException("Restore pause already armed");
        return restore = new LumiOperationTestPause(java.util.Objects.requireNonNull(boundary));
    }

    public boolean reached() { return reached; }

    public static void beforeSavePublication() {
        var pause = save;
        if (pause == null) return;
        pause.reached = true;
        try {
            if (!pause.release.await(3, TimeUnit.MINUTES)) {
                throw new IllegalStateException("Save publication test pause timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Save publication test pause interrupted", interrupted);
        }
    }

    public static boolean deferRestoreAdvance() {
        var pause = restore;
        if (pause == null || pause.release.getCount() == 0) return false;
        if (!pause.reached && !pause.restoreBoundary.getAsBoolean()) return false;
        pause.reached = true;
        if (System.nanoTime() > pause.deadline) {
            throw new IllegalStateException("Restore test pause timed out");
        }
        return true;
    }

    @Override public void close() {
        release.countDown();
        if (save == this) save = null;
        if (restore == this) restore = null;
    }
}
