package io.github.lumi.gametest;

import java.io.IOException;

/** One-shot post-apply capture failures on the native applier's thread. */
public final class LumiAxiomVerificationFault {
    private static final ThreadLocal<IOException> NEXT = new ThreadLocal<>();
    private static final ThreadLocal<BlockReadFailure> BLOCK_READ = new ThreadLocal<>();

    private LumiAxiomVerificationFault() { }

    public static void arm(IOException failure) {
        if (NEXT.get() != null) throw new IllegalStateException("Axiom verification fault already armed");
        NEXT.set(java.util.Objects.requireNonNull(failure));
    }

    public static void beforeIdentification() throws IOException {
        IOException failure = NEXT.get();
        NEXT.remove();
        if (failure != null) throw failure;
    }

    public static void armBlockRead(IOException failure, int successfulReads) {
        if (BLOCK_READ.get() != null) throw new IllegalStateException("Block read fault already armed");
        if (successfulReads < 0) throw new IllegalArgumentException("Negative read count");
        BLOCK_READ.set(new BlockReadFailure(java.util.Objects.requireNonNull(failure), successfulReads));
    }

    public static void beforeBlockRead() throws IOException {
        BlockReadFailure fault = BLOCK_READ.get();
        if (fault == null) return;
        if (fault.successfulReads == 0) {
            BLOCK_READ.remove();
            throw fault.failure;
        }
        BLOCK_READ.set(new BlockReadFailure(fault.failure, fault.successfulReads - 1));
    }

    public static void clear() { NEXT.remove(); BLOCK_READ.remove(); }

    private record BlockReadFailure(IOException failure, int successfulReads) { }
}
