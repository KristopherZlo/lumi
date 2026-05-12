package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;

/**
 * Opens the capture source used by growth hooks without placing helper types in
 * the Mixin-owned package.
 */
public final class GrowthMutationSourceScope {

    private GrowthMutationSourceScope() {
    }

    public static void runAmbient(Runnable action) {
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(WorldMutationSource.GROWTH)) {
            action.run();
        }
    }

    public static void runCausal(Runnable action) {
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushCausalSource(WorldMutationSource.GROWTH)) {
            action.run();
        }
    }
}
