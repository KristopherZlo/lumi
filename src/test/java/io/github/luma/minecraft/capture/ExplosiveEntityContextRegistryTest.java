package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosiveEntityContextRegistryTest {

    @Test
    void capturesCurrentBuilderActionAsExplosiveContext() {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true);
        try {
            String actionId = WorldMutationContext.currentActionId();

            Optional<ExplosiveEntityContextRegistry.ExplosiveContext> captured =
                    ExplosiveEntityContextRegistry.ExplosiveContext.captureCurrent();

            assertTrue(captured.isPresent());
            assertEquals(WorldMutationSource.EXPLOSIVE, captured.get().source());
            assertEquals("builder", captured.get().actor());
            assertEquals(actionId, captured.get().actionId());
            assertTrue(captured.get().accessAllowed());

            captured.get().push();
            try {
                assertEquals(WorldMutationSource.EXPLOSIVE, WorldMutationContext.currentSource());
                assertEquals("builder", WorldMutationContext.currentActor());
                assertEquals(actionId, WorldMutationContext.currentActionId());
                assertTrue(WorldMutationContext.currentAccessAllowed());
            } finally {
                WorldMutationContext.popSource();
            }
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Test
    void capturesRedstonePrimedTntActionAsExplosiveContext() {
        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true)) {
            String actionId = WorldMutationContext.currentActionId();

            try (WorldMutationContext.SourceFrame ignoredBlockUpdate =
                         WorldMutationContext.pushSource(WorldMutationSource.BLOCK_UPDATE)) {
                Optional<ExplosiveEntityContextRegistry.ExplosiveContext> captured =
                        ExplosiveEntityContextRegistry.ExplosiveContext.captureCurrent();

                assertTrue(captured.isPresent());
                assertEquals(WorldMutationSource.EXPLOSIVE, captured.get().source());
                assertEquals("builder", captured.get().actor());
                assertEquals(actionId, captured.get().actionId());
                assertTrue(captured.get().accessAllowed());
            }
        }
    }

    @Test
    void capturesChainedTntExplosionActionAsExplosiveContext() {
        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true)) {
            String actionId = WorldMutationContext.currentActionId();

            try (WorldMutationContext.SourceFrame ignoredRedstone =
                         WorldMutationContext.pushSource(WorldMutationSource.BLOCK_UPDATE)) {
                Optional<ExplosiveEntityContextRegistry.ExplosiveContext> firstTnt =
                        ExplosiveEntityContextRegistry.ExplosiveContext.captureCurrent();

                assertTrue(firstTnt.isPresent());
                firstTnt.get().push();
                try {
                    try (WorldMutationContext.SourceFrame ignoredChain =
                                 WorldMutationContext.pushSource(WorldMutationSource.EXPLOSIVE)) {
                        Optional<ExplosiveEntityContextRegistry.ExplosiveContext> chainedTnt =
                                ExplosiveEntityContextRegistry.ExplosiveContext.captureCurrent();

                        assertTrue(chainedTnt.isPresent());
                        assertEquals(WorldMutationSource.EXPLOSIVE, chainedTnt.get().source());
                        assertEquals("builder", chainedTnt.get().actor());
                        assertEquals(actionId, chainedTnt.get().actionId());
                        assertTrue(chainedTnt.get().accessAllowed());
                    }
                } finally {
                    WorldMutationContext.popSource();
                }
            }
        }
    }

    @Test
    void ignoresExplosiveContextWithoutBuilderAction() {
        assertTrue(ExplosiveEntityContextRegistry.ExplosiveContext.captureCurrent().isEmpty());
    }
}
