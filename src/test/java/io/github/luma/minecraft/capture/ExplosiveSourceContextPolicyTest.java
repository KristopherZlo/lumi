package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosiveSourceContextPolicyTest {

    private final ExplosiveSourceContextPolicy policy = new ExplosiveSourceContextPolicy();

    @Test
    void ambientEntityTriggeredTntCannotOpenExplosiveRootSource() {
        for (WorldMutationSource source : new WorldMutationSource[]{
                WorldMutationSource.MOB,
                WorldMutationSource.EXPLOSION
        }) {
            try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(source)) {
                assertFalse(this.policy.canOpenExplosiveSource(), source.name());
            }
        }
    }

    @Test
    void playerOwnedExplosiveFalloutCanOpenExplosiveRootSource() {
        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true)) {
            assertTrue(this.policy.canOpenExplosiveSource());
        }
    }

    @Test
    void redstoneAndChainedTntKeepInheritedAccess() {
        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true)) {
            try (WorldMutationContext.SourceFrame ignoredRedstone =
                         WorldMutationContext.pushSource(WorldMutationSource.BLOCK_UPDATE)) {
                assertTrue(this.policy.canOpenExplosiveSource());

                try (WorldMutationContext.SourceFrame ignoredTnt =
                             WorldMutationContext.pushSource(WorldMutationSource.EXPLOSIVE)) {
                    assertTrue(this.policy.canOpenExplosiveSource());
                    assertTrue(this.policy.canOpenExplosiveSource(true));
                }
            }
        }
    }

    @Test
    void unauthorizedContextCannotOpenExplosiveRootSource() {
        assertFalse(this.policy.canOpenExplosiveSource(false));
    }
}
