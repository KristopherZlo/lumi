package io.github.luma.minecraft.world;

import io.github.luma.domain.model.WorldMutationSource;

/**
 * Keeps internal history replay from turning restored TNT back into primed TNT.
 */
public final class TntReplayActivationPolicy {

    public boolean shouldSuppressActivation(
            boolean clientSide,
            WorldMutationSource source,
            boolean captureSuppressed
    ) {
        if (clientSide || !captureSuppressed) {
            return false;
        }
        return switch (source == null ? WorldMutationSource.SYSTEM : source) {
            case RESTORE, BLOCK_UPDATE, PISTON, FLUID, SYSTEM -> true;
            case PLAYER, ENTITY, EXPLOSION, FIRE, GROWTH, FALLING_BLOCK, EXPLOSIVE,
                    MOB, EXTERNAL_TOOL, WORLDEDIT, FAWE, AXIOM -> false;
        };
    }
}
