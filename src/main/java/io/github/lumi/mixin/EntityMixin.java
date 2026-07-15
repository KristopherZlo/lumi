package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.MinecraftLiveEntityTracker;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class EntityMixin {
    @Unique private static final ThreadLocal<Deque<Optional<PendingRemoval>>> LUMI_REMOVALS =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "remove", at = @At("HEAD"))
    private void lumi$beginRemoval(Entity.RemovalReason reason, CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        Optional<PendingRemoval> removal = Optional.empty();
        if (entity.level() instanceof ServerLevel level) {
            var runtime = LumiMod.serverRuntime().find(level).orElse(null);
            if (runtime != null) {
                try {
                    removal = runtime.liveEntities().begin(entity)
                            .map(pending -> new PendingRemoval(runtime.liveEntities(), pending));
                } catch (IOException failed) {
                    LumiMod.LOGGER.warn("Cannot capture live entity {} before removal",
                            entity.getUUID(), failed);
                }
            }
        }
        LUMI_REMOVALS.get().addLast(removal);
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void lumi$finishRemoval(Entity.RemovalReason reason, CallbackInfo callback) {
        Deque<Optional<PendingRemoval>> removals = LUMI_REMOVALS.get();
        removals.removeLast().ifPresent(removal -> {
            try {
                removal.tracker().finish(removal.pending());
            } catch (IOException failed) {
                LumiMod.LOGGER.warn("Cannot finish removed live entity {}",
                        removal.pending().entity(), failed);
            }
        });
        if (removals.isEmpty()) {
            LUMI_REMOVALS.remove();
        }
    }

    @Unique
    private record PendingRemoval(
            MinecraftLiveEntityTracker tracker,
            MinecraftLiveEntityTracker.Pending pending) { }
}
