package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.MinecraftCausalTickTracker;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
abstract class BoundTickingBlockEntityMixin {
    @Shadow @Final private BlockEntity blockEntity;
    @Unique private Optional<MinecraftCausalTickTracker.CausalExecution> lumi$scope =
            Optional.empty();

    @Inject(method = "tick", at = @At("HEAD"))
    private void lumi$beginCausalTick(CallbackInfo callback) {
        lumi$scope = blockEntity.getLevel() instanceof ServerLevel level
                ? LumiMod.serverRuntime().find(level)
                        .flatMap(runtime -> runtime.causalTicks().resumeCarrier(blockEntity))
                : Optional.empty();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void lumi$endCausalTick(CallbackInfo callback) {
        if (blockEntity.getLevel() instanceof ServerLevel level) {
            LumiMod.serverRuntime().find(level).ifPresent(runtime -> {
                try {
                    runtime.blockEntityChanged(blockEntity);
                } catch (IOException failed) {
                    LumiMod.LOGGER.warn("Cannot finish causal block entity {}",
                            blockEntity.getBlockPos(), failed);
                }
                runtime.causalTicks().finishedCarrier(blockEntity);
            });
        }
        lumi$scope.ifPresent(MinecraftCausalTickTracker.CausalExecution::close);
        lumi$scope = Optional.empty();
    }
}
