package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.axiom.packets.AxiomServerboundSetBlock;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** Captures Axiom set-block requests and joins each Fast Place packet burst. */
@Mixin(value = AxiomServerboundSetBlock.class, remap = false)
abstract class AxiomSetBlockMixin {
    private static final MinecraftSectionCapture LUMI_SECTIONS = new MinecraftSectionCapture();

    /** Tinker and no-updates bypass LevelChunk.setBlockState and its capture hooks. */
    @WrapMethod(method = "setWithoutUpdates")
    private static void lumi$trackRawBlock(
            ServerLevel level, BlockPos position, BlockState state, Operation<Void> original) {
        var runtime = LumiMod.serverRuntime().find(level).orElseThrow();
        if (!runtime.freeze().isMutationAllowed()) return;
        if (runtime.freeze().isAuthorizedMutation()
                || level.isOutsideBuildHeight(position)) {
            original.call(level, position, state);
            return;
        }
        var action = DirectLiveActionContext.current(runtime.liveActions()).orElseThrow();
        var chunk = level.getChunk(position.getX() >> 4, position.getZ() >> 4);
        var key = MinecraftSectionCapture.key(position);
        var block = new BlockPosition(position.getX(), position.getY(), position.getZ());
        try {
            var before = runtime.liveBlocks().beforeMutation(block, runtime.liveWorld().read(block));
            long generation = runtime.mutations().registerSectionMutation(key, () -> {
                try {
                    return LUMI_SECTIONS.capture(level, chunk, key.sectionY());
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            runtime.blockEntityBaselines().discard(key);
            boolean ownsCapture = runtime.liveBlocks().beginBlockMutation(block);
            try {
                original.call(level, position, state);
            } finally {
                try {
                    var after = runtime.liveWorld().read(block);
                    runtime.liveBlocks().record(action, block, before, after);
                    if (!before.equals(after)) {
                        runtime.mutations().recordBuilderBlockMutation(block, generation);
                    }
                } catch (IOException | RuntimeException failure) {
                    runtime.liveActions().makeUnavailable(action, "Cannot verify the Axiom edit after mutation");
                    runtime.mutations().markBuilderMutation(key);
                    LumiMod.LOGGER.error("Cannot capture Axiom no-updates edit at {}", position, failure);
                } finally {
                    if (ownsCapture) runtime.liveBlocks().endBlockMutation(block);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot capture Axiom block before mutation", failure);
        }
    }

    @WrapMethod(method = "handle")
    private void lumi$trackSetBlock(
            MinecraftServer server,
            ServerPlayer player,
            Operation<Void> original) {
        var runtime = LumiMod.serverRuntime().find(player.level()).orElse(null);
        if (runtime == null) {
            original.call(server, player);
            return;
        }
        if (!runtime.freeze().isMutationAllowed()) {
            return;
        }
        UUID action;
        try (var ignored = DirectLiveActionContext.open(
                runtime.liveActions(), player.getUUID())) {
            action = DirectLiveActionContext.current(
                    runtime.liveActions()).orElseThrow();
            original.call(server, player);
        }
        runtime.axiomSetBlockActions().join(
                player.getUUID(), action, server.getTickCount());
    }
}
