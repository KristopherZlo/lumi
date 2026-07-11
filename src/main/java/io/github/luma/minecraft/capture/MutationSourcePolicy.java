package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorldMutationSource;
import java.util.EnumSet;

/**
 * Capture policy for mutation-source permissions, bootstrapping, and attribution.
 */
final class MutationSourcePolicy {

    private static final EnumSet<WorldMutationSource> EXPLICIT_ROOT_SOURCES = EnumSet.of(
            WorldMutationSource.PLAYER,
            WorldMutationSource.ENTITY,
            WorldMutationSource.EXPLOSIVE,
            WorldMutationSource.EXTERNAL_TOOL,
            WorldMutationSource.WORLDEDIT,
            WorldMutationSource.FAWE,
            WorldMutationSource.AXIOM
    );
    private static final EnumSet<WorldMutationSource> TRACKED_CHUNK_EXPANSION_SOURCES = EnumSet.of(
            WorldMutationSource.PLAYER,
            WorldMutationSource.ENTITY,
            WorldMutationSource.EXPLOSION,
            WorldMutationSource.FIRE,
            WorldMutationSource.GROWTH,
            WorldMutationSource.MOB,
            WorldMutationSource.EXPLOSIVE,
            WorldMutationSource.EXTERNAL_TOOL,
            WorldMutationSource.WORLDEDIT,
            WorldMutationSource.FAWE,
            WorldMutationSource.AXIOM
    );
    private static final EnumSet<WorldMutationSource> ACTIVE_REGION_REQUIRED_SOURCES = EnumSet.of(
            WorldMutationSource.FLUID,
            WorldMutationSource.FIRE,
            WorldMutationSource.BLOCK_UPDATE,
            WorldMutationSource.PISTON,
            WorldMutationSource.FALLING_BLOCK
    );
    private static final EnumSet<WorldMutationSource> REUSABLE_DEFERRED_CONTEXT_SOURCES = EnumSet.of(
            WorldMutationSource.BLOCK_UPDATE,
            WorldMutationSource.PISTON,
            WorldMutationSource.FLUID,
            WorldMutationSource.FALLING_BLOCK,
            WorldMutationSource.GROWTH,
            WorldMutationSource.SYSTEM
    );
    private static final EnumSet<WorldMutationSource> DIRECT_CAPTURE_REQUIRES_ACTION_SOURCES = EnumSet.of(
            WorldMutationSource.EXPLOSION,
            WorldMutationSource.FIRE,
            WorldMutationSource.GROWTH,
            WorldMutationSource.FLUID,
            WorldMutationSource.FALLING_BLOCK,
            WorldMutationSource.MOB
    );
    boolean isExplicitRootSource(WorldMutationSource source) {
        return source != null && EXPLICIT_ROOT_SOURCES.contains(source);
    }

    boolean allowsAutomaticProjectCreation(WorldMutationSource source) {
        return source == WorldMutationSource.PLAYER
                || source == WorldMutationSource.ENTITY
                || source == WorldMutationSource.EXPLOSIVE;
    }

    boolean allowsSessionBootstrap(WorldMutationSource source) {
        return this.isExplicitRootSource(source);
    }

    boolean allowsCausalSessionBootstrap(WorldMutationSource source, String actionId) {
        return (source == WorldMutationSource.EXPLOSION
                || source == WorldMutationSource.GROWTH
                || source == WorldMutationSource.MOB)
                && this.hasCausalAction(actionId);
    }

    boolean allowsTrackedChunkExpansion(WorldMutationSource source) {
        return source != null && TRACKED_CHUNK_EXPANSION_SOURCES.contains(source);
    }

    boolean allowsTrackedChunkExpansion(WorldMutationSource source, boolean activeSessionRegion) {
        return this.allowsTrackedChunkExpansion(source)
                || (activeSessionRegion && this.requiresActiveRegionMembership(source));
    }

    boolean requiresActiveRegionMembership(WorldMutationSource source) {
        return source != null && ACTIVE_REGION_REQUIRED_SOURCES.contains(source);
    }

    boolean canUse(boolean dedicatedServer, boolean accessAllowed, WorldMutationSource source) {
        return !this.isExplicitRootSource(source) || accessAllowed;
    }

    boolean usesDeferredStabilization(BuildProject project, WorldMutationSource source) {
        if (project == null || source == null) {
            return false;
        }
        return source == WorldMutationSource.BLOCK_UPDATE
                || source == WorldMutationSource.PISTON
                || (project.tracksWholeDimension()
                && (source == WorldMutationSource.FLUID || source == WorldMutationSource.FALLING_BLOCK));
    }

    boolean usesLiveStateReconciliation(WorldMutationSource source) {
        return source != null && source != WorldMutationSource.GROWTH;
    }

    boolean canCaptureDeferredPreMutationBaseline(
            BuildProject project,
            WorldMutationSource source,
            boolean activeSessionRegion,
            String actionId
    ) {
        if (project == null || source == null) {
            return false;
        }
        if (source == WorldMutationSource.GROWTH) {
            return this.hasCausalAction(actionId);
        }
        return activeSessionRegion
                && this.requiresActiveRegionMembership(source)
                && this.usesDeferredStabilization(project, source)
                && this.canUseDeferredStabilization(project, source, activeSessionRegion, actionId);
    }

    boolean requiresCausalActionForDeferredStabilization(WorldMutationSource source) {
        return source == WorldMutationSource.BLOCK_UPDATE
                || source == WorldMutationSource.PISTON
                || source == WorldMutationSource.FLUID
                || source == WorldMutationSource.FALLING_BLOCK;
    }

    boolean canUseDeferredStabilization(WorldMutationSource source, String actionId) {
        return !this.requiresCausalActionForDeferredStabilization(source)
                || this.hasCausalAction(actionId);
    }

    boolean canUseDeferredStabilization(BuildProject project, WorldMutationSource source, String actionId) {
        return this.usesDeferredStabilization(project, source)
                && this.canUseDeferredStabilization(source, actionId);
    }

    boolean canUseDeferredStabilization(
            BuildProject project,
            WorldMutationSource source,
            boolean activeSessionRegion,
            String actionId
    ) {
        return this.usesDeferredStabilization(project, source)
                && activeSessionRegion
                && this.canUseDeferredStabilization(source, actionId);
    }

    boolean canInspectBlockMutationPayload(
            BuildProject project,
            WorldMutationSource source,
            boolean hasActiveSession,
            boolean activeSessionRegion,
            String actionId
    ) {
        if (source == null) {
            return false;
        }
        if (this.usesDeferredStabilization(project, source)) {
            return this.canUseDeferredStabilization(project, source, activeSessionRegion, actionId);
        }
        if (!hasActiveSession) {
            return this.allowsSessionBootstrap(source) || this.allowsCausalSessionBootstrap(source, actionId);
        }
        if (this.requiresActiveRegionMembership(source) && !activeSessionRegion) {
            return false;
        }
        return this.canUseDirectCapture(source, actionId);
    }

    boolean canReuseDeferredActionContext(WorldMutationSource source) {
        return source != null && REUSABLE_DEFERRED_CONTEXT_SOURCES.contains(source);
    }

    boolean requiresCausalActionForDirectCapture(WorldMutationSource source) {
        return source != null && DIRECT_CAPTURE_REQUIRES_ACTION_SOURCES.contains(source);
    }

    boolean canUseDirectCapture(WorldMutationSource source, String actionId) {
        return !this.requiresCausalActionForDirectCapture(source)
                || this.hasCausalAction(actionId);
    }

    private boolean hasCausalAction(String actionId) {
        return actionId != null && !actionId.isBlank();
    }

    String defaultActor(WorldMutationSource source) {
        if (source == null) {
            return "world";
        }
        return switch (source) {
            case PLAYER -> "player";
            case ENTITY -> "entity";
            case EXPLOSION -> "explosion";
            case FLUID -> "fluid";
            case FIRE -> "fire";
            case GROWTH -> "growth";
            case BLOCK_UPDATE -> "block-update";
            case PISTON -> "piston";
            case FALLING_BLOCK -> "falling-block";
            case EXPLOSIVE -> "explosive";
            case MOB -> "mob";
            case EXTERNAL_TOOL -> "external-tool";
            case WORLDEDIT -> "worldedit";
            case FAWE -> "fawe";
            case AXIOM -> "axiom";
            case RESTORE, SYSTEM -> "world";
        };
    }
}
