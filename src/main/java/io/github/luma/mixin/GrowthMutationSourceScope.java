package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;

final class GrowthMutationSourceScope {

    private GrowthMutationSourceScope() {
    }

    static void runAmbient(GrowthCall call) {
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(WorldMutationSource.GROWTH)) {
            call.run();
        }
    }

    static void runCausal(GrowthCall call) {
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushCausalSource(WorldMutationSource.GROWTH)) {
            call.run();
        }
    }

    @FunctionalInterface
    interface GrowthCall {

        void run();
    }
}
