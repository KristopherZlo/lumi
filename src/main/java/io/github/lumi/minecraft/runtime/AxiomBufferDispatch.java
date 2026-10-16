package io.github.lumi.minecraft.runtime;

import java.util.UUID;

/** Preserves the native wire identity that Axiom otherwise discards during decoding. */
public interface AxiomBufferDispatch {
    void lumi$dispatch(UUID dispatch);
}
