package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorldMutationSource;

/**
 * Capture policy for mutation-source permissions, bootstrapping, and attribution.
 */
final class MutationSourcePolicy {

    boolean isExplicitRootSource(WorldMutationSource source) {
        if (source == null) {
            return false;
        }
        return switch (source) {
            case PLAYER, ENTITY, EXPLOSIVE, EXTERNAL_TOOL, WORLDEDIT, FAWE, AXIOM -> true;
            case EXPLOSION, FLUID, FIRE, GROWTH, BLOCK_UPDATE, PISTON, FALLING_BLOCK, MOB, RESTORE, SYSTEM -> false;
        };
    }

    boolean allowsAutomaticProjectCreation(WorldMutationSource source) {
        return this.isExplicitRootSource(source);
    }

    boolean allowsSessionBootstrap(WorldMutationSource source) {
        return this.isExplicitRootSource(source);
    }

    boolean allowsTrackedChunkExpansion(WorldMutationSource source) {
        if (source == null) {
            return false;
        }
        return switch (source) {
            case PLAYER,
                    ENTITY,
                    EXPLOSION,
                    FIRE,
                    BLOCK_UPDATE,
                    MOB,
                    EXPLOSIVE,
                    EXTERNAL_TOOL,
                    WORLDEDIT,
                    FAWE,
                    AXIOM -> true;
            case FLUID,
                    GROWTH,
                    PISTON,
                    FALLING_BLOCK -> false;
            case RESTORE, SYSTEM -> false;
        };
    }

    boolean requiresActiveRegionMembership(WorldMutationSource source) {
        if (source == null) {
            return false;
        }
        return switch (source) {
            case EXPLOSION,
                    FLUID,
                    FIRE,
                    GROWTH,
                    BLOCK_UPDATE,
                    FALLING_BLOCK,
                    MOB -> true;
            case PLAYER,
                    ENTITY,
                    EXPLOSIVE,
                    EXTERNAL_TOOL,
                    WORLDEDIT,
                    FAWE,
                    AXIOM,
                    PISTON,
                    RESTORE,
                    SYSTEM -> false;
        };
    }

    boolean canUse(boolean dedicatedServer, boolean accessAllowed, WorldMutationSource source) {
        return !this.isExplicitRootSource(source) || accessAllowed || !dedicatedServer;
    }

    boolean usesDeferredStabilization(BuildProject project, WorldMutationSource source) {
        if (project == null || source == null) {
            return false;
        }
        return project.tracksWholeDimension()
                && (source == WorldMutationSource.FLUID || source == WorldMutationSource.FALLING_BLOCK);
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
