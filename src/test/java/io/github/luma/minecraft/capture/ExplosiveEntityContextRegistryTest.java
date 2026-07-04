package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.WorldMutationSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
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

    @Test
    void capturesDeferredDispenserTntActionAsExplosiveContext() {
        CaptureSessionState.DeferredActionContext deferred =
                new CaptureSessionState.DeferredActionContext("action-1", "builder", true);

        Optional<ExplosiveEntityContextRegistry.ExplosiveContext> captured =
                ExplosiveEntityContextRegistry.ExplosiveContext.captureDeferred(deferred);

        assertTrue(captured.isPresent());
        assertEquals(WorldMutationSource.EXPLOSIVE, captured.get().source());
        assertEquals("builder", captured.get().actor());
        assertEquals("action-1", captured.get().actionId());
        assertTrue(captured.get().accessAllowed());
    }

    @Test
    void logsExplosiveContextLifecycleForTntDiagnostics() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/capture/ExplosiveEntityContextRegistry.java")
        );

        assertTrue(source.contains("activeContextCount()"));
        assertTrue(source.contains("LumaLoadLog.event(\"tnt-context\", \"remember\""));
        assertTrue(source.contains("LumaLoadLog.event(\"tnt-context\", \"forget\""));
        assertTrue(source.contains("LumaLoadLog.event(\"tnt-context\", \"spawn-context\""));
        assertTrue(source.contains("origin=\" + origin"));
    }

    @Test
    void reportsActiveContextsUntilTheyExpire() {
        ExplosiveEntityContextRegistry registry = new ExplosiveEntityContextRegistry();
        UUID activeId = UUID.randomUUID();
        registry.remember(activeId, new ExplosiveEntityContextRegistry.ExplosiveContext(
                WorldMutationSource.EXPLOSIVE,
                "builder",
                "action-1",
                true,
                System.currentTimeMillis()
        ));

        assertTrue(registry.hasActiveContexts());

        registry.remember(UUID.randomUUID(), new ExplosiveEntityContextRegistry.ExplosiveContext(
                WorldMutationSource.EXPLOSIVE,
                "builder",
                "action-2",
                true,
                System.currentTimeMillis() - 121_000L
        ));
        registry.forget(activeId);

        assertTrue(!registry.hasActiveContexts());
    }
}
