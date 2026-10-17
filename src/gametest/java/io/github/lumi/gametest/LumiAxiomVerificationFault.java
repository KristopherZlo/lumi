package io.github.lumi.gametest;

import java.io.IOException;

/** One-shot canonical-identification failure on the native applier's thread. */
public final class LumiAxiomVerificationFault {
    private static final ThreadLocal<IOException> NEXT = new ThreadLocal<>();

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

    public static void clear() { NEXT.remove(); }
}
