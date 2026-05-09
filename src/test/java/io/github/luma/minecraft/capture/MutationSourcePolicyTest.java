package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationSourcePolicyTest {

    private final MutationSourcePolicy policy = new MutationSourcePolicy();

    @Test
    void deferredStabilizationIsLimitedToCausalPhysicsSources() {
        BuildProject wholeDimension = BuildProject.createWorldWorkspace(
                "World",
                "minecraft:overworld",
                Instant.parse("2026-04-28T10:00:00Z")
        );
        BuildProject bounded = BuildProject.create(
                "Area",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 80, 15)),
                new BlockPoint(0, 64, 0),
                Instant.parse("2026-04-28T10:00:00Z")
        );

        assertTrue(this.policy.usesDeferredStabilization(wholeDimension, WorldMutationSource.FLUID));
        assertTrue(this.policy.usesDeferredStabilization(wholeDimension, WorldMutationSource.FALLING_BLOCK));
        assertTrue(this.policy.usesDeferredStabilization(wholeDimension, WorldMutationSource.BLOCK_UPDATE));
        assertTrue(this.policy.usesDeferredStabilization(wholeDimension, WorldMutationSource.PISTON));
        assertFalse(this.policy.usesDeferredStabilization(wholeDimension, WorldMutationSource.EXPLOSION));
        assertFalse(this.policy.usesDeferredStabilization(wholeDimension, WorldMutationSource.EXPLOSIVE));
        assertFalse(this.policy.usesDeferredStabilization(wholeDimension, WorldMutationSource.FIRE));
        assertFalse(this.policy.usesDeferredStabilization(bounded, WorldMutationSource.FLUID));
        assertTrue(this.policy.usesDeferredStabilization(bounded, WorldMutationSource.BLOCK_UPDATE));
        assertTrue(this.policy.usesDeferredStabilization(bounded, WorldMutationSource.PISTON));
    }

    @Test
    void deferredMechanismStabilizationRequiresCausalAction() {
        assertFalse(this.policy.canUseDeferredStabilization(WorldMutationSource.BLOCK_UPDATE, ""));
        assertFalse(this.policy.canUseDeferredStabilization(WorldMutationSource.PISTON, null));

        assertTrue(this.policy.canUseDeferredStabilization(WorldMutationSource.BLOCK_UPDATE, "action-1"));
        assertTrue(this.policy.canUseDeferredStabilization(WorldMutationSource.PISTON, "action-1"));
    }

    @Test
    void deferredPhysicsStabilizationRequiresCausalAction() {
        assertFalse(this.policy.canUseDeferredStabilization(WorldMutationSource.FLUID, ""));
        assertFalse(this.policy.canUseDeferredStabilization(WorldMutationSource.FALLING_BLOCK, null));

        assertTrue(this.policy.canUseDeferredStabilization(WorldMutationSource.FLUID, "action-1"));
        assertTrue(this.policy.canUseDeferredStabilization(WorldMutationSource.FALLING_BLOCK, "action-1"));
    }

    @Test
    void ambientGrowthDirectCaptureRequiresCausalAction() {
        assertTrue(this.policy.requiresCausalActionForDirectCapture(WorldMutationSource.GROWTH));
        assertFalse(this.policy.canUseDirectCapture(WorldMutationSource.GROWTH, ""));
        assertFalse(this.policy.canUseDirectCapture(WorldMutationSource.GROWTH, null));

        assertTrue(this.policy.canUseDirectCapture(WorldMutationSource.GROWTH, "action-1"));
        assertFalse(this.policy.requiresCausalActionForDirectCapture(WorldMutationSource.FIRE));
        assertTrue(this.policy.canUseDirectCapture(WorldMutationSource.FIRE, ""));
    }

    @Test
    void deferredPreMutationBaselineRequiresActiveSessionRegionAndCausalAction() {
        BuildProject wholeDimension = BuildProject.createWorldWorkspace(
                "World",
                "minecraft:overworld",
                Instant.parse("2026-04-28T10:00:00Z")
        );
        BuildProject bounded = BuildProject.create(
                "Area",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 80, 15)),
                new BlockPoint(0, 64, 0),
                Instant.parse("2026-04-28T10:00:00Z")
        );

        assertFalse(this.policy.canCaptureDeferredPreMutationBaseline(
                wholeDimension,
                WorldMutationSource.PISTON,
                false,
                "action-1"
        ));
        assertTrue(this.policy.canCaptureDeferredPreMutationBaseline(
                wholeDimension,
                WorldMutationSource.PISTON,
                true,
                "action-1"
        ));
        assertTrue(this.policy.canCaptureDeferredPreMutationBaseline(
                wholeDimension,
                WorldMutationSource.FLUID,
                true,
                "action-1"
        ));
        assertFalse(this.policy.canCaptureDeferredPreMutationBaseline(
                wholeDimension,
                WorldMutationSource.FLUID,
                true,
                ""
        ));
        assertFalse(this.policy.canCaptureDeferredPreMutationBaseline(
                bounded,
                WorldMutationSource.FLUID,
                true,
                "action-1"
        ));
        assertFalse(this.policy.canCaptureDeferredPreMutationBaseline(
                wholeDimension,
                WorldMutationSource.PLAYER,
                true,
                "action-1"
        ));
    }

    @Test
    void activeSessionRegionCanExpandTrackedChunksForSecondarySources() {
        assertFalse(this.policy.allowsTrackedChunkExpansion(WorldMutationSource.FLUID, false));
        assertFalse(this.policy.allowsTrackedChunkExpansion(WorldMutationSource.GROWTH, false));
        assertFalse(this.policy.allowsTrackedChunkExpansion(WorldMutationSource.BLOCK_UPDATE, false));
        assertFalse(this.policy.allowsTrackedChunkExpansion(WorldMutationSource.PISTON, false));
        assertFalse(this.policy.allowsTrackedChunkExpansion(WorldMutationSource.FALLING_BLOCK, false));

        assertTrue(this.policy.allowsTrackedChunkExpansion(WorldMutationSource.FLUID, true));
        assertTrue(this.policy.allowsTrackedChunkExpansion(WorldMutationSource.GROWTH, true));
        assertTrue(this.policy.allowsTrackedChunkExpansion(WorldMutationSource.BLOCK_UPDATE, true));
        assertTrue(this.policy.allowsTrackedChunkExpansion(WorldMutationSource.PISTON, true));
        assertTrue(this.policy.allowsTrackedChunkExpansion(WorldMutationSource.FALLING_BLOCK, true));
        assertFalse(this.policy.allowsTrackedChunkExpansion(WorldMutationSource.SYSTEM, true));
    }
}
