package io.github.luma.minecraft.capture;

/**
 * Gates TNT/explosive source promotion to mutations that belong to an undoable
 * action.
 */
public final class ExplosiveSourceContextPolicy {

    public boolean canOpenExplosiveSource() {
        return this.canOpenExplosiveSource(WorldMutationContext.currentActionId());
    }

    boolean canOpenExplosiveSource(String actionId) {
        return actionId != null && !actionId.isBlank();
    }
}
