package io.github.luma.minecraft.capture;

/**
 * Mixin access for vanilla carriers that need to retain the originating Lumi
 * action until their delayed world mutation is processed.
 */
public interface DeferredWorldMutationContextAccess {

    DeferredWorldMutationContext luma$deferredMutationContext();

    void luma$setDeferredMutationContext(DeferredWorldMutationContext context);
}
