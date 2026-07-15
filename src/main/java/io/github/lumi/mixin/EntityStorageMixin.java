package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.EntityStorageLevelAccess;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityStorage.class)
abstract class EntityStorageMixin implements EntityStorageLevelAccess {
    @Shadow @Final private ServerLevel level;

    @Override
    public ServerLevel lumi$level() {
        return level;
    }

    @Inject(method = "loadEntities", at = @At("RETURN"), cancellable = true)
    private void lumi$rememberEntityBaseline(
            ChunkPos position,
            CallbackInfoReturnable<CompletableFuture<ChunkEntities<Entity>>> callback) {
        callback.setReturnValue(callback.getReturnValue().thenApply(chunk -> {
            var runtime = LumiMod.serverRuntime().find(level).orElse(null);
            if (runtime != null) {
                try {
                    runtime.entityChunkLoaded(chunk);
                } catch (IOException failed) {
                    throw new CompletionException("Cannot capture Lumi entity baseline", failed);
                }
            }
            return chunk;
        }));
    }
}
