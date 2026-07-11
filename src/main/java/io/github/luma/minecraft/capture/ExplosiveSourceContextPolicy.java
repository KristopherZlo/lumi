package io.github.luma.minecraft.capture;

/**
 * Gates TNT source promotion to builder-authorized mutation chains.
 */
public final class ExplosiveSourceContextPolicy {

    public boolean canOpenExplosiveSource() {
        return this.canOpenExplosiveSource(WorldMutationContext.currentAccessAllowed());
    }

    boolean canOpenExplosiveSource(boolean accessAllowed) {
        return accessAllowed;
    }
}
