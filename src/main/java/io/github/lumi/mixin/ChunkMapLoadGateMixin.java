package io.github.lumi.mixin;

import io.github.lumi.minecraft.world.ChunkLoadGate;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
abstract class ChunkMapLoadGateMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "prepareAccessibleChunk", at = @At("HEAD"), cancellable = true)
    private void lumi$gateAccessibleChunk(
            ChunkHolder holder,
            CallbackInfoReturnable<CompletableFuture<ChunkResult<LevelChunk>>> callback) {
        gate(holder, callback);
    }

    @Inject(method = "prepareTickingChunk", at = @At("HEAD"), cancellable = true)
    private void lumi$gateTickingChunk(
            ChunkHolder holder,
            CallbackInfoReturnable<CompletableFuture<ChunkResult<LevelChunk>>> callback) {
        gate(holder, callback);
    }

    @Inject(method = "prepareEntityTickingChunk", at = @At("HEAD"), cancellable = true)
    private void lumi$gateEntityTickingChunk(
            ChunkHolder holder,
            CallbackInfoReturnable<CompletableFuture<ChunkResult<LevelChunk>>> callback) {
        gate(holder, callback);
    }

    private void gate(
            ChunkHolder holder,
            CallbackInfoReturnable<CompletableFuture<ChunkResult<LevelChunk>>> callback) {
        if (ChunkLoadGate.isGated(level, holder.getPos())) {
            callback.setReturnValue(CompletableFuture.completedFuture(
                    ChunkResult.error("Lumi Restore storage gate")));
        }
    }
}
