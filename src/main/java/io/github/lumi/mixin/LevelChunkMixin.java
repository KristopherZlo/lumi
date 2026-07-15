package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
    private static final MinecraftSectionCapture LUMI_SECTION_CAPTURE = new MinecraftSectionCapture();

    @Shadow @Final private Level level;

    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"), cancellable = true)
    private void lumi$trackBlockMutation(
            BlockPos position, BlockState update, int flags,
            CallbackInfoReturnable<BlockState> callback) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        LumiMod.serverRuntime().find(serverLevel).ifPresent(runtime -> {
            LevelChunk chunk = (LevelChunk) (Object) this;
            BlockState current = chunk.getBlockState(position);
            if (!runtime.freeze().isMutationAllowed()) {
                callback.setReturnValue(current);
                return;
            }
            if (runtime.freeze().isAuthorizedMutation() || current.equals(update)) {
                return;
            }
            var key = MinecraftSectionCapture.key(position);
            runtime.mutations().registerSectionMutation(key, () -> {
                try {
                    return LUMI_SECTION_CAPTURE.capture(serverLevel, chunk, key.sectionY());
                } catch (IOException failed) {
                    throw new UncheckedIOException("Cannot capture pre-mutation Lumi section", failed);
                }
            });
        });
    }
}
