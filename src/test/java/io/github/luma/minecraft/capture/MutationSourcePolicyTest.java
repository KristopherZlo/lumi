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
            assertTrue(this.policy.canInspectBlockMutationPayload(this.project, source, true, true, false), source.name());
        }
    }

    @Test
    void secondarySourcesCannotEscapeTheActiveRegion() {
        assertFalse(this.policy.canInspectBlockMutationPayload(
                this.project,
                WorldMutationSource.EXPLOSION,
                true,
                false,
                false
        ));
        assertFalse(this.policy.canInspectBlockMutationPayload(
                this.project,
                WorldMutationSource.FLUID,
                true,
                false,
                false
        ));
        assertTrue(this.policy.canInspectBlockMutationPayload(
                this.project,
                WorldMutationSource.PLAYER,
                true,
                false,
                false
        ));
    }

    @Test
    void causalSecondarySourcesBootstrapAndEscapeTheActiveRegion() {
        assertTrue(this.policy.allowsSessionBootstrap(WorldMutationSource.EXPLOSION, true));
        assertTrue(this.policy.canInspectBlockMutationPayload(
                this.project,
                WorldMutationSource.EXPLOSION,
                false,
                false,
                true
        ));
        assertTrue(this.policy.allowsTrackedChunkExpansion(
                WorldMutationSource.PISTON,
                false,
                true
        ));
        assertTrue(this.policy.canUseDeferredStabilization(
                this.project,
                WorldMutationSource.BLOCK_UPDATE,
                false,
                true
        ));
    }

    @Test
    void causalFlagNeverEnablesInternalSources() {
        assertFalse(this.policy.allowsSessionBootstrap(WorldMutationSource.RESTORE, true));
        assertFalse(this.policy.allowsSessionBootstrap(WorldMutationSource.SYSTEM, true));
    }

    @Test
    void deferredStabilizationNeedsTheActiveRegion() {
        assertTrue(this.policy.canUseDeferredStabilization(
                this.project,
                WorldMutationSource.BLOCK_UPDATE,
                true,
                false
        ));
        assertFalse(this.policy.canUseDeferredStabilization(
                this.project,
                WorldMutationSource.BLOCK_UPDATE,
                false,
                false
        ));
    }
}
