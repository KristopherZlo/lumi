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
    boolean isExplicitRootSource(WorldMutationSource source) {
        return source != null && EXPLICIT_ROOT_SOURCES.contains(source);
    }

    boolean allowsAutomaticProjectCreation(WorldMutationSource source) {
        return source == WorldMutationSource.PLAYER
                || source == WorldMutationSource.ENTITY
                || source == WorldMutationSource.EXPLOSIVE;
    }

    boolean allowsSessionBootstrap(WorldMutationSource source) {
        return this.allowsSessionBootstrap(source, false);
    }

    boolean allowsSessionBootstrap(WorldMutationSource source, boolean causalAction) {
        return this.isExplicitRootSource(source) || this.isCausalSecondarySource(source, causalAction);
    }

    boolean allowsTrackedChunkExpansion(WorldMutationSource source) {
        return source != null && TRACKED_CHUNK_EXPANSION_SOURCES.contains(source);
    }

    boolean allowsTrackedChunkExpansion(
            WorldMutationSource source,
            boolean activeSessionRegion,
            boolean causalAction
    ) {
        return this.allowsTrackedChunkExpansion(source)
                || (activeSessionRegion && this.requiresActiveRegionMembership(source))
                || this.isCausalSecondarySource(source, causalAction);
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
        return source != null;
    }

    boolean canUseDeferredStabilization(
            BuildProject project,
            WorldMutationSource source,
            boolean activeSessionRegion,
            boolean causalAction
    ) {
        return this.usesDeferredStabilization(project, source)
                && (activeSessionRegion || this.isCausalSecondarySource(source, causalAction));
    }

    boolean canInspectBlockMutationPayload(
            BuildProject project,
            WorldMutationSource source,
            boolean hasActiveSession,
            boolean activeSessionRegion,
            boolean causalAction
    ) {
        if (source == null) {
            return false;
        }
        if (this.isCausalSecondarySource(source, causalAction)) {
            return true;
        }
        if (hasActiveSession && activeSessionRegion) {
            return true;
        }
        if (this.usesDeferredStabilization(project, source)) {
            return this.canUseDeferredStabilization(project, source, activeSessionRegion, false);
        }
        if (!hasActiveSession) {
            return this.allowsSessionBootstrap(source);
        }
        if (this.requiresActiveRegionMembership(source) && !activeSessionRegion) {
            return false;
        }
        return this.isExplicitRootSource(source);
    }

    private boolean isCausalSecondarySource(WorldMutationSource source, boolean causalAction) {
        return causalAction
                && source != null
                && source != WorldMutationSource.RESTORE
                && source != WorldMutationSource.SYSTEM;
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
