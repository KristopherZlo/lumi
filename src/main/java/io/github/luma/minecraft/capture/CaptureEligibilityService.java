package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorldMutationSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Owns source/access and causal-action gates for capture entry points.
 */
final class CaptureEligibilityService {

    private final WorldMutationCapturePolicy capturePolicy = new WorldMutationCapturePolicy();
    private final MutationSourcePolicy sourcePolicy = new MutationSourcePolicy();

    boolean shouldCaptureMutation(WorldMutationSource source) {
        return this.capturePolicy.shouldCaptureMutation(source);
    }

    WorldMutationCapturePolicy.CaptureResult evaluateBlockMutation(
            WorldMutationSource source,
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            CompoundTag oldBlockEntity,
            CompoundTag newBlockEntity
    ) {
        return this.capturePolicy.evaluate(source, pos, oldState, newState, oldBlockEntity, newBlockEntity);
    }

    boolean hiddenInBuilderSurfaces(WorldMutationSource source) {
        return this.capturePolicy.hiddenInBuilderSurfaces(source);
    }

    boolean isExplicitRootSource(WorldMutationSource source) {
        return this.sourcePolicy.isExplicitRootSource(source);
    }

    boolean allowsAutomaticProjectCreation(WorldMutationSource source) {
        return this.sourcePolicy.allowsAutomaticProjectCreation(source);
    }

    boolean allowsSessionBootstrap(WorldMutationSource source) {
        return this.sourcePolicy.allowsSessionBootstrap(source);
    }

    boolean allowsSessionBootstrap(WorldMutationSource source, String actionId) {
        return this.sourcePolicy.allowsSessionBootstrap(source)
                || this.sourcePolicy.allowsCausalSessionBootstrap(source, actionId);
    }

    boolean allowsTrackedChunkExpansion(WorldMutationSource source) {
        return this.sourcePolicy.allowsTrackedChunkExpansion(source);
    }

    boolean allowsTrackedChunkExpansion(WorldMutationSource source, boolean activeSessionRegion) {
        return this.sourcePolicy.allowsTrackedChunkExpansion(source, activeSessionRegion);
    }

    boolean requiresActiveRegionMembership(WorldMutationSource source) {
        return this.sourcePolicy.requiresActiveRegionMembership(source);
    }

    boolean canUseMutationSource(boolean dedicatedServer, boolean accessAllowed, WorldMutationSource source) {
        return this.sourcePolicy.canUse(dedicatedServer, accessAllowed, source);
    }

    boolean usesDeferredStabilization(BuildProject project, WorldMutationSource source) {
        return this.sourcePolicy.usesDeferredStabilization(project, source);
    }

    boolean usesLiveStateReconciliation(WorldMutationSource source) {
        return this.sourcePolicy.usesLiveStateReconciliation(source);
    }

    boolean isLiveUndoOnlySource(WorldMutationSource source) {
        return this.sourcePolicy.isLiveUndoOnlySource(source);
    }

    boolean canCaptureDeferredPreMutationBaseline(
            BuildProject project,
            WorldMutationSource source,
            boolean activeSessionRegion,
            String actionId
    ) {
        return this.sourcePolicy.canCaptureDeferredPreMutationBaseline(project, source, activeSessionRegion, actionId);
    }

    boolean canUseDeferredStabilization(
            BuildProject project,
            WorldMutationSource source,
            boolean activeSessionRegion,
            String actionId
    ) {
        return this.sourcePolicy.canUseDeferredStabilization(project, source, activeSessionRegion, actionId);
    }

    boolean canInspectBlockMutationPayload(
            BuildProject project,
            WorldMutationSource source,
            boolean hasActiveSession,
            boolean activeSessionRegion,
            String actionId
    ) {
        return this.sourcePolicy.canInspectBlockMutationPayload(
                project,
                source,
                hasActiveSession,
                activeSessionRegion,
                actionId
        );
    }

    boolean canUseDirectCapture(WorldMutationSource source, String actionId) {
        return this.sourcePolicy.canUseDirectCapture(source, actionId);
    }

    boolean canReuseDeferredActionContext(WorldMutationSource source) {
        return this.sourcePolicy.canReuseDeferredActionContext(source);
    }

    String defaultActor(WorldMutationSource source) {
        return this.sourcePolicy.defaultActor(source);
    }
}
