package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldMutationContextTest {

    @Test
    void causalActionRequiresAnAuthorizedActionIdentity() {
        assertFalse(WorldMutationContext.hasCausalAction());

        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushPlayerSource(
                WorldMutationSource.PLAYER,
                "builder",
                true
        )) {
            assertTrue(WorldMutationContext.hasCausalAction());
        }

        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(
                WorldMutationSource.EXPLOSION,
                "builder",
                "action-1",
                false
        )) {
            assertFalse(WorldMutationContext.hasCausalAction());
        }
    }

    @Test
    void externalSourcePreservesToolActorAndActionId() {
        WorldMutationContext.pushExternalSource(WorldMutationSource.WORLDEDIT, "worldedit:builder", "action-1", true);
        try {
            assertEquals(WorldMutationSource.WORLDEDIT, WorldMutationContext.currentSource());
            assertEquals("worldedit:builder", WorldMutationContext.currentActor());
            assertEquals("action-1", WorldMutationContext.currentActionId());
            assertTrue(WorldMutationContext.currentAccessAllowed());
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Test
    void externalSourceDefaultsToDeniedWithoutExplicitAccess() {
        WorldMutationContext.pushExternalSource(WorldMutationSource.WORLDEDIT, "worldedit:builder", "action-1");
        try {
            assertEquals(WorldMutationSource.WORLDEDIT, WorldMutationContext.currentSource());
            assertFalse(WorldMutationContext.currentAccessAllowed());
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Test
    void ambientSourceDoesNotInheritPlayerActionIdentity() {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true);
        String playerActionId = WorldMutationContext.currentActionId();
        try {
            WorldMutationContext.pushSource(WorldMutationSource.GROWTH);
            try {
                assertEquals(WorldMutationSource.GROWTH, WorldMutationContext.currentSource());
                assertEquals("growth", WorldMutationContext.currentActor());
                assertEquals("", WorldMutationContext.currentActionId());
                assertFalse(WorldMutationContext.currentAccessAllowed());
            } finally {
                WorldMutationContext.popSource();
            }

            assertEquals(WorldMutationSource.PLAYER, WorldMutationContext.currentSource());
            assertEquals("builder", WorldMutationContext.currentActor());
            assertEquals(playerActionId, WorldMutationContext.currentActionId());
            assertTrue(WorldMutationContext.currentAccessAllowed());
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Test
    void causalSecondarySourceCanInheritPlayerActionIdentity() {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true);
        String playerActionId = WorldMutationContext.currentActionId();
        try {
            WorldMutationContext.pushCausalSource(WorldMutationSource.GROWTH);
            try {
                assertEquals(WorldMutationSource.GROWTH, WorldMutationContext.currentSource());
                assertEquals("builder", WorldMutationContext.currentActor());
                assertEquals(playerActionId, WorldMutationContext.currentActionId());
                assertTrue(WorldMutationContext.currentAccessAllowed());
            } finally {
                WorldMutationContext.popSource();
            }
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Test
    void nestedGrowthSourceKeepsCausalGrowthActionIdentity() {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true);
        String playerActionId = WorldMutationContext.currentActionId();
        try {
            WorldMutationContext.pushCausalSource(WorldMutationSource.GROWTH);
            try {
                WorldMutationContext.pushSource(WorldMutationSource.GROWTH);
                try {
                    assertEquals(WorldMutationSource.GROWTH, WorldMutationContext.currentSource());
                    assertEquals("builder", WorldMutationContext.currentActor());
                    assertEquals(playerActionId, WorldMutationContext.currentActionId());
                    assertTrue(WorldMutationContext.currentAccessAllowed());
                } finally {
                    WorldMutationContext.popSource();
                }
            } finally {
                WorldMutationContext.popSource();
            }
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Test
    void explicitExplosiveSourceKeepsPlayerActionIdentity() {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true);
        String playerActionId = WorldMutationContext.currentActionId();
        try {
            WorldMutationContext.pushSource(WorldMutationSource.EXPLOSIVE);
            try {
                assertEquals(WorldMutationSource.EXPLOSIVE, WorldMutationContext.currentSource());
                assertEquals("builder", WorldMutationContext.currentActor());
                assertEquals(playerActionId, WorldMutationContext.currentActionId());
                assertTrue(WorldMutationContext.currentAccessAllowed());
            } finally {
                WorldMutationContext.popSource();
            }
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Test
    void nestedPlayerSourceInheritsActivePlayerActionIdentity() {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true);
        String playerActionId = WorldMutationContext.currentActionId();
        try {
            WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "nested-builder", false);
            try {
                assertEquals(WorldMutationSource.PLAYER, WorldMutationContext.currentSource());
                assertEquals("builder", WorldMutationContext.currentActor());
                assertEquals(playerActionId, WorldMutationContext.currentActionId());
                assertTrue(WorldMutationContext.currentAccessAllowed());
            } finally {
                WorldMutationContext.popSource();
            }

            assertEquals(WorldMutationSource.PLAYER, WorldMutationContext.currentSource());
            assertEquals("builder", WorldMutationContext.currentActor());
            assertEquals(playerActionId, WorldMutationContext.currentActionId());
            assertTrue(WorldMutationContext.currentAccessAllowed());
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Test
    void nestedPlayerSourceCanUpgradeAccessWithinActivePlayerAction() {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", false);
        String playerActionId = WorldMutationContext.currentActionId();
        try {
            WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "nested-builder", true);
            try {
                assertEquals("builder", WorldMutationContext.currentActor());
                assertEquals(playerActionId, WorldMutationContext.currentActionId());
                assertTrue(WorldMutationContext.currentAccessAllowed());
            } finally {
                WorldMutationContext.popSource();
            }

            assertEquals(playerActionId, WorldMutationContext.currentActionId());
            assertFalse(WorldMutationContext.currentAccessAllowed());
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Test
    void playerSurvivalModeFlagIsInheritedByCausalSources() {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true, true);
        String playerActionId = WorldMutationContext.currentActionId();
        try {
            assertTrue(WorldMutationContext.currentSurvivalMode());

            WorldMutationContext.pushSource(WorldMutationSource.BLOCK_UPDATE);
            try {
                assertEquals(playerActionId, WorldMutationContext.currentActionId());
                assertTrue(WorldMutationContext.currentSurvivalMode());
            } finally {
                WorldMutationContext.popSource();
            }
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Test
    void sourceFrameClosesAfterException() {
        assertEquals(WorldMutationSource.SYSTEM, WorldMutationContext.currentSource());

        assertThrows(IllegalStateException.class, () -> {
            try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(WorldMutationSource.RESTORE)) {
                assertEquals(WorldMutationSource.RESTORE, WorldMutationContext.currentSource());
                throw new IllegalStateException("boom");
            }
        });

        assertEquals(WorldMutationSource.SYSTEM, WorldMutationContext.currentSource());
    }

    @Test
    void internalWorldApplyTracksRestoreSourceOnly() {
        assertFalse(WorldMutationContext.internalWorldApplyActive());

        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(WorldMutationSource.RESTORE)) {
            assertTrue(WorldMutationContext.internalWorldApplyActive());
        }

        assertFalse(WorldMutationContext.internalWorldApplyActive());
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(WorldMutationSource.PISTON)) {
            assertFalse(WorldMutationContext.internalWorldApplyActive());
        }
    }

    @Test
    void deferredMechanismSourcesKeepPlayerActionIdentity() {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true);
        String playerActionId = WorldMutationContext.currentActionId();
        try {
            WorldMutationContext.pushSource(WorldMutationSource.BLOCK_UPDATE);
            try {
                assertEquals(WorldMutationSource.BLOCK_UPDATE, WorldMutationContext.currentSource());
                assertEquals("builder", WorldMutationContext.currentActor());
                assertEquals(playerActionId, WorldMutationContext.currentActionId());
                assertTrue(WorldMutationContext.currentAccessAllowed());

                WorldMutationContext.pushSource(WorldMutationSource.PISTON);
                try {
                    assertEquals(WorldMutationSource.PISTON, WorldMutationContext.currentSource());
                    assertEquals("builder", WorldMutationContext.currentActor());
                    assertEquals(playerActionId, WorldMutationContext.currentActionId());
                    assertTrue(WorldMutationContext.currentAccessAllowed());
                } finally {
                    WorldMutationContext.popSource();
                }
            } finally {
                WorldMutationContext.popSource();
            }
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Test
    void deferredContextWithoutActionIdKeepsActorAndAccess() {
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(
                WorldMutationSource.BLOCK_UPDATE,
                "builder",
                "",
                true
        )) {
            try (WorldMutationContext.SourceFrame nested = WorldMutationContext.pushSource(WorldMutationSource.PISTON)) {
                assertEquals(WorldMutationSource.PISTON, WorldMutationContext.currentSource());
                assertEquals("builder", WorldMutationContext.currentActor());
                assertEquals("", WorldMutationContext.currentActionId());
                assertTrue(WorldMutationContext.currentAccessAllowed());
            }
        }
    }

    @Test
    void captureSuppressionIsScoped() {
        assertFalse(WorldMutationContext.captureSuppressed());

        WorldMutationContext.runWithCaptureSuppressed(() -> {
            assertTrue(WorldMutationContext.captureSuppressed());
            WorldMutationContext.runWithCaptureSuppressed(() -> assertTrue(WorldMutationContext.captureSuppressed()));
            assertTrue(WorldMutationContext.captureSuppressed());
        });

        assertFalse(WorldMutationContext.captureSuppressed());
    }

    @Test
    void suppressionFrameClosesAfterException() {
        assertFalse(WorldMutationContext.captureSuppressed());

        assertThrows(IllegalStateException.class, () -> {
            try (WorldMutationContext.SuppressionFrame ignored = WorldMutationContext.pushCaptureSuppression()) {
                assertTrue(WorldMutationContext.captureSuppressed());
                throw new IllegalStateException("boom");
            }
        });

        assertFalse(WorldMutationContext.captureSuppressed());
    }

    @Test
    void captureSuppressionCanSpanMixinBoundary() {
        assertFalse(WorldMutationContext.captureSuppressed());

        WorldMutationContext.pushCaptureSuppression();
        try {
            assertTrue(WorldMutationContext.captureSuppressed());
            WorldMutationContext.pushCaptureSuppression();
            try {
                assertTrue(WorldMutationContext.captureSuppressed());
            } finally {
                WorldMutationContext.popCaptureSuppression();
            }
            assertTrue(WorldMutationContext.captureSuppressed());
        } finally {
            WorldMutationContext.popCaptureSuppression();
        }

        assertFalse(WorldMutationContext.captureSuppressed());
    }

    @Test
    void historyEntityReplayFrameIsScoped() {
        assertFalse(WorldMutationContext.historyEntityReplayActive());

        try (WorldMutationContext.EntityReplayFrame ignored = WorldMutationContext.pushHistoryEntityReplay()) {
            assertTrue(WorldMutationContext.historyEntityReplayActive());
            try (WorldMutationContext.EntityReplayFrame nested = WorldMutationContext.pushHistoryEntityReplay()) {
                assertTrue(WorldMutationContext.historyEntityReplayActive());
            }
            assertTrue(WorldMutationContext.historyEntityReplayActive());
        }

        assertFalse(WorldMutationContext.historyEntityReplayActive());
    }
}
