package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
    private static final MinecraftSectionCapture LUMI_SECTION_CAPTURE = new MinecraftSectionCapture();
    @Unique private static final ThreadLocal<Deque<Optional<PendingLiveBlock>>> LUMI_LIVE_BLOCKS =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Shadow @Final private Level level;
    @Shadow private boolean loaded;

    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"), cancellable = true)
    private void lumi$trackBlockMutation(
            BlockPos position, BlockState update, int flags,
            CallbackInfoReturnable<BlockState> callback) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        LevelChunk chunk = (LevelChunk) (Object) this;
        BlockState current = chunk.getBlockState(position);
        Optional<PendingLiveBlock> pending = Optional.empty();
        if (!current.equals(update)) {
            if (!lumi$trackSectionBeforeMutation(serverLevel, position)) {
                callback.setReturnValue(current);
            } else {
                pending = lumi$captureLiveBefore(serverLevel, position);
            }
        }
        LUMI_LIVE_BLOCKS.get().addLast(pending);
    }

    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void lumi$finishLiveBlockMutation(
            BlockPos position, BlockState update, int flags,
            CallbackInfoReturnable<BlockState> callback) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        Deque<Optional<PendingLiveBlock>> stack = LUMI_LIVE_BLOCKS.get();
        Optional<PendingLiveBlock> pending = stack.removeLast();
        if (stack.isEmpty()) {
            LUMI_LIVE_BLOCKS.remove();
        }
        pending.ifPresent(value -> lumi$recordLiveAfter(serverLevel, position, value));
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void lumi$trackBlockEntityAdd(BlockEntity blockEntity, CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel
                && !lumi$trackSectionBeforeMutation(serverLevel, blockEntity.getBlockPos())) {
            callback.cancel();
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void lumi$trackBlockEntityRemoval(BlockPos position, CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel
                && !lumi$trackSectionBeforeMutation(serverLevel, position)) {
            callback.cancel();
        }
    }

    private boolean lumi$trackSectionBeforeMutation(ServerLevel serverLevel, BlockPos position) {
        if (!loaded) {
            return true;
        }
        var runtime = LumiMod.serverRuntime().find(serverLevel).orElse(null);
        if (runtime == null) {
            return true;
        }
        if (!runtime.freeze().isMutationAllowed()) {
            return false;
        }
        if (runtime.freeze().isAuthorizedMutation()) {
            return true;
        }
        LevelChunk chunk = (LevelChunk) (Object) this;
        var key = MinecraftSectionCapture.key(position);
        runtime.mutations().registerSectionMutation(key, () -> {
            try {
                return LUMI_SECTION_CAPTURE.capture(serverLevel, chunk, key.sectionY());
            } catch (IOException failed) {
                throw new UncheckedIOException("Cannot capture pre-mutation Lumi section", failed);
            }
        });
        runtime.blockEntityBaselines().discard(key);
        return true;
    }

    @Unique
    private Optional<PendingLiveBlock> lumi$captureLiveBefore(
            ServerLevel serverLevel, BlockPos position) {
        var runtime = LumiMod.serverRuntime().find(serverLevel).orElse(null);
        if (runtime == null) {
            return Optional.empty();
        }
        Optional<UUID> action = DirectLiveActionContext.current(runtime.liveActions());
        if (action.isEmpty()) {
            return Optional.empty();
        }
        try {
            BlockPosition key = new BlockPosition(position.getX(), position.getY(), position.getZ());
            return Optional.of(new PendingLiveBlock(
                    action.orElseThrow(), key, runtime.liveWorld().read(key)));
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Cannot capture live block before mutation at {}", position, failed);
            return Optional.empty();
        }
    }

    @Unique
    private void lumi$recordLiveAfter(
            ServerLevel serverLevel, BlockPos position, PendingLiveBlock pending) {
        var runtime = LumiMod.serverRuntime().find(serverLevel).orElse(null);
        if (runtime == null) {
            return;
        }
        try {
            BlockSnapshot after = runtime.liveWorld().read(pending.position);
            if (!pending.before.equals(after)) {
                runtime.liveActions().record(
                        pending.action, pending.position, pending.before, after);
            }
        } catch (IOException | IllegalArgumentException failed) {
            LumiMod.LOGGER.warn("Cannot finish live block mutation at {}", position, failed);
        }
    }

    @Unique
    private record PendingLiveBlock(UUID action, BlockPosition position, BlockSnapshot before) { }
}
