package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
abstract class ChunkMapMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(
            method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At("HEAD"), cancellable = true)
    private void lumi$gateVanillaChunkSave(
            ChunkAccess chunk, CallbackInfoReturnable<Boolean> callback) {
        LumiMod.serverRuntime().find(level).ifPresent(runtime -> {
            if (!runtime.mutations().canPublishChunk(chunk.getPos().x, chunk.getPos().z)) {
                callback.setReturnValue(false);
            }
        });
    }
}
