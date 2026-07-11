package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationSourcePolicyTest {

    private final MutationSourcePolicy policy = new MutationSourcePolicy();
    private final BuildProject project = BuildProject.createWorldWorkspace(
            "World",
            "minecraft:overworld",
            Instant.parse("2026-04-28T10:00:00Z")
    );

    @Test
    void onlyExplicitBuilderSourcesBootstrapSessions() {
        assertTrue(this.policy.allowsSessionBootstrap(WorldMutationSource.PLAYER));
        assertTrue(this.policy.allowsSessionBootstrap(WorldMutationSource.EXTERNAL_TOOL));
        assertFalse(this.policy.allowsSessionBootstrap(WorldMutationSource.EXPLOSION));
        assertFalse(this.policy.allowsSessionBootstrap(WorldMutationSource.FLUID));
    }

    @Test
    void activeRegionAcceptsEveryPersistentFalloutSource() {
        for (WorldMutationSource source : WorldMutationSource.values()) {
            if (source == WorldMutationSource.RESTORE || source == WorldMutationSource.SYSTEM) {
                continue;
            }
            assertTrue(this.policy.canInspectBlockMutationPayload(this.project, source, true, true), source.name());
        }
    }

    @Test
    void secondarySourcesCannotEscapeTheActiveRegion() {
        assertFalse(this.policy.canInspectBlockMutationPayload(
                this.project,
                WorldMutationSource.EXPLOSION,
                true,
                false
        ));
        assertFalse(this.policy.canInspectBlockMutationPayload(
                this.project,
                WorldMutationSource.FLUID,
                true,
                false
        ));
        assertTrue(this.policy.canInspectBlockMutationPayload(
                this.project,
                WorldMutationSource.PLAYER,
                true,
                false
        ));
    }

    @Test
    void deferredStabilizationNeedsTheActiveRegion() {
        assertTrue(this.policy.canUseDeferredStabilization(
                this.project,
                WorldMutationSource.BLOCK_UPDATE,
                true
        ));
        assertFalse(this.policy.canUseDeferredStabilization(
                this.project,
                WorldMutationSource.BLOCK_UPDATE,
                false
        ));
    }
}
