package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
abstract class BlockEntityMixin {
    private static final MinecraftSectionCapture LUMI_SECTION_CAPTURE = new MinecraftSectionCapture();

    @Inject(method = "setChanged", at = @At("HEAD"))
    private void lumi$trackBlockEntityDataChange(CallbackInfo callback) {
        BlockEntity blockEntity = (BlockEntity) (Object) this;
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) {
            return;
        }
        LumiMod.serverRuntime().find(level).ifPresent(runtime -> {
            if (runtime.freeze().isAuthorizedMutation()) {
                return;
            }
            var key = MinecraftSectionCapture.key(blockEntity.getBlockPos());
            if (runtime.mutations().needsOrigin(key)
                    && !runtime.blockEntityBaselines().contains(key)) {
                return;
            }
            LevelChunk chunk = level.getChunkAt(blockEntity.getBlockPos());
            long generation = runtime.mutations().registerSectionMutation(key, () -> {
                try {
                    var current = LUMI_SECTION_CAPTURE.capture(level, chunk, key.sectionY());
                    return runtime.blockEntityBaselines().takeOrigin(key, current)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Missing pre-mutation block-entity baseline for " + key));
                } catch (IOException failed) {
                    throw new UncheckedIOException("Cannot capture Lumi block-entity mutation", failed);
                }
            });
            var position = blockEntity.getBlockPos();
            var changed = new BlockPosition(
                    position.getX(), position.getY(), position.getZ());
            if (DirectLiveActionContext.current(runtime.liveActions()).isPresent()) {
                runtime.mutations().recordBuilderBlockMutation(changed, generation);
            } else {
                runtime.mutations().recordBlockMutation(changed, generation);
            }
        });
    }
}
