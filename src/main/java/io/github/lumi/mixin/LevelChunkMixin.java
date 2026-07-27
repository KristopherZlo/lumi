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
    @Unique private static final long LUMI_MUTATION_DENIED = -1L;
    @Unique private static final long LUMI_MUTATION_UNTRACKED = 0L;
    private static final MinecraftSectionCapture LUMI_SECTION_CAPTURE = new MinecraftSectionCapture();
    @Unique private static final ThreadLocal<Deque<Optional<PendingBlockMutation>>> LUMI_LIVE_BLOCKS =
            ThreadLocal.withInitial(ArrayDeque::new);

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
        LevelChunk chunk = (LevelChunk) (Object) this;
        BlockState current = chunk.getBlockState(position);
        Optional<PendingBlockMutation> pending = Optional.empty();
        if (!current.equals(update)) {
            long generation = lumi$trackSectionBeforeMutation(serverLevel, position);
            if (generation == LUMI_MUTATION_DENIED) {
                callback.setReturnValue(current);
            } else {
                boolean builder = LumiMod.serverRuntime().find(serverLevel)
                        .flatMap(runtime -> DirectLiveActionContext.current(
                                runtime.liveActions()))
                        .isPresent();
                pending = Optional.of(new PendingBlockMutation(
                        current, generation, builder,
                        lumi$captureLiveBefore(serverLevel, position)));
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
        Deque<Optional<PendingBlockMutation>> stack = LUMI_LIVE_BLOCKS.get();
        Optional<PendingBlockMutation> pending = stack.removeLast();
        if (stack.isEmpty()) {
            LUMI_LIVE_BLOCKS.remove();
        }
        pending.ifPresent(value -> {
            LevelChunk chunk = (LevelChunk) (Object) this;
            if (value.before().equals(chunk.getBlockState(position))) {
                return;
            }
            var runtime = LumiMod.serverRuntime().find(serverLevel).orElse(null);
            if (runtime != null && value.generation() > LUMI_MUTATION_UNTRACKED) {
                BlockPosition changed = blockPosition(position);
                if (value.builder()) {
                    runtime.mutations().recordBuilderBlockMutation(
                            changed, value.generation());
                } else {
                    runtime.mutations().recordBlockMutation(
                            changed, value.generation());
                }
            }
            value.live().ifPresent(live ->
                    lumi$recordLiveAfter(serverLevel, position, live));
        });
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void lumi$trackBlockEntityAdd(BlockEntity blockEntity, CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel) {
            LumiMod.serverRuntime().find(serverLevel).ifPresent(runtime ->
                    runtime.causalTicks().rememberCarrier(blockEntity));
            long generation = lumi$trackSectionBeforeMutation(
                    serverLevel, blockEntity.getBlockPos());
            if (generation == LUMI_MUTATION_DENIED) {
                callback.cancel();
            } else if (generation > LUMI_MUTATION_UNTRACKED) {
                LumiMod.serverRuntime().find(serverLevel).ifPresent(runtime -> {
                    BlockPosition changed = blockPosition(blockEntity.getBlockPos());
                    if (DirectLiveActionContext.current(runtime.liveActions()).isPresent()) {
                        runtime.mutations().recordBuilderBlockMutation(
                                changed, generation);
                    } else {
                        runtime.mutations().recordBlockMutation(changed, generation);
                    }
                });
            }
        }
    }

    @Inject(method = "setBlockEntity", at = @At("RETURN"))
    private void lumi$finishBlockEntityAdd(BlockEntity blockEntity, CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel
                && ((LevelChunk) (Object) this).getBlockEntity(
                        blockEntity.getBlockPos()) == blockEntity) {
            LumiMod.serverRuntime().find(serverLevel).ifPresent(runtime -> {
                try {
                    runtime.liveBlockEntities().added(blockEntity);
                } catch (IOException failed) {
                    LumiMod.LOGGER.warn("Cannot capture added live block entity {}",
                            blockEntity.getBlockPos(), failed);
                }
            });
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void lumi$trackBlockEntityRemoval(BlockPos position, CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel) {
            LumiMod.serverRuntime().find(serverLevel).ifPresent(runtime -> {
                try {
                    runtime.liveBlockEntities().removing(position);
                } catch (IOException failed) {
                    LumiMod.LOGGER.warn("Cannot capture removed live block entity {}",
                            position, failed);
                }
            });
            long generation = lumi$trackSectionBeforeMutation(serverLevel, position);
            if (generation == LUMI_MUTATION_DENIED) {
                callback.cancel();
            } else if (generation > LUMI_MUTATION_UNTRACKED) {
                LumiMod.serverRuntime().find(serverLevel).ifPresent(runtime -> {
                    BlockPosition changed = blockPosition(position);
                    if (DirectLiveActionContext.current(runtime.liveActions()).isPresent()) {
                        runtime.mutations().recordBuilderBlockMutation(
                                changed, generation);
                    } else {
                        runtime.mutations().recordBlockMutation(changed, generation);
                    }
                });
            }
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("RETURN"))
    private void lumi$finishBlockEntityRemoval(BlockPos position, CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel
                && ((LevelChunk) (Object) this).getBlockEntity(position) == null) {
            LumiMod.serverRuntime().find(serverLevel).ifPresent(runtime ->
                    runtime.liveBlockEntities().removed(position));
        }
    }

    private long lumi$trackSectionBeforeMutation(
            ServerLevel serverLevel, BlockPos position) {
        var runtime = LumiMod.serverRuntime().find(serverLevel).orElse(null);
        if (runtime == null) {
            return LUMI_MUTATION_UNTRACKED;
        }
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (!runtime.isChunkMutationTrackable(
                chunk.getPos().x, chunk.getPos().z)) {
            return LUMI_MUTATION_UNTRACKED;
        }
        if (!runtime.freeze().isMutationAllowed()) {
            return LUMI_MUTATION_DENIED;
        }
        if (runtime.freeze().isAuthorizedMutation()) {
            return LUMI_MUTATION_UNTRACKED;
        }
        var key = MinecraftSectionCapture.key(position);
        long generation = runtime.mutations().registerSectionMutation(key, () -> {
            try {
                return LUMI_SECTION_CAPTURE.capture(serverLevel, chunk, key.sectionY());
            } catch (IOException failed) {
                throw new UncheckedIOException("Cannot capture pre-mutation Lumi section", failed);
            }
        });
        runtime.blockEntityBaselines().discard(key);
        return generation;
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
            BlockPosition key = blockPosition(position);
            boolean nested = LUMI_LIVE_BLOCKS.get().stream()
                    .flatMap(Optional::stream)
                    .flatMap(pending -> pending.live().stream())
                    .filter(pending -> pending.action().equals(action.orElseThrow())
                            && pending.position().equals(key))
                    .findFirst().isPresent();
            if (nested) {
                return Optional.empty();
            }
            BlockSnapshot before = runtime.liveBlockEntities().beforeMutation(
                    key, runtime.liveWorld().read(key));
            return Optional.of(new PendingLiveBlock(
                    action.orElseThrow(), key, before));
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
            runtime.liveBlockEntities().completedBlockMutation(
                    pending.position, after);
            if (!pending.before.equals(after)) {
                BlockEntity carrier = serverLevel.getBlockEntity(position);
                if (carrier != null) {
                    runtime.causalTicks().rememberCarrier(carrier);
                }
                UUID effective = runtime.liveActions().record(
                        pending.action, pending.position, pending.before, after);
                if (!effective.equals(pending.action)) {
                    runtime.liveActions().mergeGroups(pending.action, effective);
                }
                runtime.recordCausalZoneGrowth(pending.action, pending.position);
            }
        } catch (IOException | IllegalArgumentException failed) {
            LumiMod.LOGGER.warn("Cannot finish live block mutation at {}", position, failed);
        }
    }

    @Unique
    private record PendingLiveBlock(UUID action, BlockPosition position, BlockSnapshot before) { }

    @Unique
    private static BlockPosition blockPosition(BlockPos position) {
        return new BlockPosition(position.getX(), position.getY(), position.getZ());
    }

    @Unique
    private record PendingBlockMutation(
            BlockState before,
            long generation,
            boolean builder,
            Optional<PendingLiveBlock> live) { }
}
