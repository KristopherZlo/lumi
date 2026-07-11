package io.github.luma.minecraft.capture;

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
    void payloadInspectionUsesSourceAndActiveRegionOnly() {
        BuildProject project = BuildProject.createWorldWorkspace(
                "World",
                "minecraft:overworld",
                Instant.parse("2026-04-28T10:00:00Z")
        );

        assertTrue(this.eligibility.canInspectBlockMutationPayload(
                project,
                WorldMutationSource.PLAYER,
                false,
                false
        ));
        assertTrue(this.eligibility.canInspectBlockMutationPayload(
                project,
                WorldMutationSource.GROWTH,
                true,
                true
        ));
        assertFalse(this.eligibility.canInspectBlockMutationPayload(
                project,
                WorldMutationSource.GROWTH,
                false,
                false
        ));
    }
}
