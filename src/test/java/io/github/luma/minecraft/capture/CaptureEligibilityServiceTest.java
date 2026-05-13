package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureEligibilityServiceTest {

    private final CaptureEligibilityService eligibility = new CaptureEligibilityService();

    @Test
    void explicitSourcesRequireAccess() {
        assertFalse(this.eligibility.canUseMutationSource(false, false, WorldMutationSource.PLAYER));
        assertTrue(this.eligibility.canUseMutationSource(false, true, WorldMutationSource.PLAYER));
        assertTrue(this.eligibility.canUseMutationSource(false, false, WorldMutationSource.FLUID));
    }

    @Test
    void ambientDirectCaptureRequiresCausalAction() {
        assertFalse(this.eligibility.canUseDirectCapture(WorldMutationSource.EXPLOSION, ""));
        assertFalse(this.eligibility.canUseDirectCapture(WorldMutationSource.FALLING_BLOCK, null));
        assertTrue(this.eligibility.canUseDirectCapture(WorldMutationSource.EXPLOSION, "action-1"));
        assertTrue(this.eligibility.canUseDirectCapture(WorldMutationSource.PLAYER, ""));
    }

    @Test
    void deferredBaselineRequiresActiveRegionAndCausalAction() {
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

        assertFalse(this.eligibility.canCaptureDeferredPreMutationBaseline(
                wholeDimension,
                WorldMutationSource.FLUID,
                false,
                "action-1"
        ));
        assertFalse(this.eligibility.canCaptureDeferredPreMutationBaseline(
                wholeDimension,
                WorldMutationSource.FLUID,
                true,
                ""
        ));
        assertTrue(this.eligibility.canCaptureDeferredPreMutationBaseline(
                wholeDimension,
                WorldMutationSource.FLUID,
                true,
                "action-1"
        ));
        assertFalse(this.eligibility.canCaptureDeferredPreMutationBaseline(
                bounded,
                WorldMutationSource.FLUID,
                true,
                "action-1"
        ));
    }
}
