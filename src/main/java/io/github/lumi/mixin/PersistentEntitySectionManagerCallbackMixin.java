package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.world.MinecraftEntityChunkCapture;
import java.io.IOException;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks both entity chunks dirty before vanilla moves an entity between them. */
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
abstract class PersistentEntitySectionManagerCallbackMixin {
    @Shadow @Final private EntityAccess entity;
    @Shadow private long currentSectionKey;

    @Inject(method = "onMove", at = @At("HEAD"))
    private void lumi$captureChunkMove(CallbackInfo callback) {
        if (!(entity instanceof Entity moved)
                || !(moved.level() instanceof ServerLevel level)
                || !MinecraftEntityChunkCapture.isDurableRoot(moved)) {
            return;
        }
        var runtime = LumiMod.serverRuntime().find(level).orElse(null);
        if (runtime == null) {
            return;
        }
        try {
            runtime.entityMoving(moved, new ChunkPos(
                    SectionPos.x(currentSectionKey),
                    SectionPos.z(currentSectionKey)));
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Cannot register moved durable entity {}",
                    moved.getUUID(), failed);
        }
    }
}
