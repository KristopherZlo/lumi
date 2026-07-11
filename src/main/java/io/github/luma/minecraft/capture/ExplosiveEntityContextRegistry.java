package io.github.luma.minecraft.capture;

import io.github.luma.debug.LumaLoadLog;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;

/** Applies the deferred mutation context stored directly on primed TNT. */
public final class ExplosiveEntityContextRegistry {

    private static final ExplosiveEntityContextRegistry INSTANCE = new ExplosiveEntityContextRegistry();

    private ExplosiveEntityContextRegistry() {
    }

    public static ExplosiveEntityContextRegistry getInstance() {
        return INSTANCE;
    }

    public boolean pushContext(Entity entity) {
        if (!(entity instanceof PrimedTnt)) {
            return false;
        }
        DeferredWorldMutationContext context = DeferredWorldMutationContexts.context(entity);
        if (context == null) {
            return false;
        }
        context.push();
        return true;
    }

    public void forget(Entity entity) {
        if (!(entity instanceof DeferredWorldMutationContextAccess access)) {
            return;
        }
        access.luma$setDeferredMutationContext(null);
        LumaLoadLog.event("tnt-context", "forget", "uuid=" + entity.getUUID());
    }
}
