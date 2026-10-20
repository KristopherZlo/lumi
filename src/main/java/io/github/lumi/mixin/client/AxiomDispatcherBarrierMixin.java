package io.github.lumi.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.moulberry.axiom.world_modification.Dispatcher;
import com.moulberry.axiom.world_modification.HistoryBuffer;
import com.moulberry.axiom.world_modification.HistoryEntry;
import com.moulberry.axiom.world_modification.BlockOrBiomeBuffer;
import com.moulberry.axiom.world_modification.BlockBuffer;
import com.moulberry.axiom.world_modification.BiomeBuffer;
import com.moulberry.axiom.world_modification.ChunkDataConsumer;
import com.moulberry.axiom.world_modification.EntityDataConsumer;
import it.unimi.dsi.fastutil.longs.LongSet;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.lumi.client.integration.AxiomHistoryCaptureScope;
import io.github.lumi.LumiMod;
import io.github.lumi.client.integration.AxiomClientWorkBarrier;
import io.github.lumi.client.integration.AxiomWorldHistory;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fences both native remote queue admission and the separate integrated-server submission. */
@Mixin(value = Dispatcher.class, remap = false)
abstract class AxiomDispatcherBarrierMixin {
    @Inject(method = "tryLoadHistory", at = @At("RETURN"))
    private static void lumi$invalidateOtherWorkspaceHistory(String identifier, CallbackInfo callback) {
        AxiomClientWorkBarrier.historyChanged(identifier);
    }

    @Inject(method = {"apply", "applyBlockBuffer", "applyBiomeBuffer", "push"}, at = @At("HEAD"), cancellable = true)
    private static void lumi$guardAdmission(CallbackInfo callback) {
        if (AxiomClientWorkBarrier.isPaused() || !AxiomWorldHistory.INSTANCE.admissionReady()) callback.cancel();
    }

    @WrapMethod(method = "push")
    private static void lumi$identifyNativeEntry(HistoryEntry<BlockOrBiomeBuffer> entry, Operation<Void> original) {
        original.call(AxiomWorldHistory.INSTANCE.prepareNativePush(entry));
    }

    @WrapMethod(method = "requestChunkData")
    private static void lumi$chunkCallbackScope(LongSet blocks, LongSet entities, boolean accurate, ChunkDataConsumer callback, Operation<Void> original) {
        var token = AxiomHistoryCaptureScope.capture();
        var replay = io.github.lumi.client.integration.AxiomNativeReplayClient.current();
        if (replay != null) replay.retain();
        original.call(blocks, entities, accurate, (ChunkDataConsumer) (nbt, states) -> {
            try { io.github.lumi.client.integration.AxiomNativeReplayClient.run(replay, () -> AxiomHistoryCaptureScope.run(token, () -> callback.accept(nbt, states))); }
            finally { if (replay != null) replay.release(); }
        });
    }

    @WrapMethod(method = "requestEntityData")
    private static void lumi$entityCallbackScope(java.util.List<java.util.UUID> entities, EntityDataConsumer callback, Operation<Void> original) {
        var token = AxiomHistoryCaptureScope.capture();
        var replay = io.github.lumi.client.integration.AxiomNativeReplayClient.current();
        if (replay != null) replay.retain();
        original.call(entities, (EntityDataConsumer) data -> {
            try { io.github.lumi.client.integration.AxiomNativeReplayClient.run(replay, () -> AxiomHistoryCaptureScope.run(token, () -> callback.accept(data))); }
            finally { if (replay != null) replay.release(); }
        });
    }

    // Selection/placement cancellation has already run; stop before the world cursor moves.
    @Inject(method = "undo(ZZZ)V", at = @At(value = "FIELD", ordinal = 0,
            target = "Lcom/moulberry/axiom/world_modification/Dispatcher;history:Lcom/moulberry/axiom/world_modification/HistoryBuffer;"), cancellable = true)
    private static void lumi$guardWorldHistory(boolean placement, boolean selection, boolean inGame, CallbackInfo callback) {
        if (AxiomClientWorkBarrier.isPaused() || AxiomWorldHistory.INSTANCE.step(true, placement, selection, inGame)) callback.cancel();
    }

    @Inject(method = "redo(ZZ)V", at = @At(value = "FIELD", ordinal = 0,
            target = "Lcom/moulberry/axiom/world_modification/Dispatcher;history:Lcom/moulberry/axiom/world_modification/HistoryBuffer;"), cancellable = true)
    private static void lumi$sharedRedo(boolean placement, boolean inGame, CallbackInfo callback) {
        if (AxiomClientWorkBarrier.isPaused() || AxiomWorldHistory.INSTANCE.step(false, placement, false, inGame)) callback.cancel();
    }

    // Admission already selected a world entry; do not consume a newly changed local selection.
    @WrapOperation(method = "undo(ZZZ)V", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lcom/moulberry/axiom/world_modification/HistoryBuffer;undo(I)Lcom/moulberry/axiom/world_modification/HistoryEntry;"))
    private static HistoryEntry<?> lumi$worldUndo(HistoryBuffer<?> history, int mask, Operation<HistoryEntry<?>> original) {
        return io.github.lumi.client.integration.AxiomNativeReplayClient.current() != null ? null : original.call(history, mask);
    }

    @WrapOperation(method = "redo(ZZ)V", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lcom/moulberry/axiom/world_modification/HistoryBuffer;redo(I)Lcom/moulberry/axiom/world_modification/HistoryEntry;"))
    private static HistoryEntry<?> lumi$worldRedo(HistoryBuffer<?> history, int mask, Operation<HistoryEntry<?>> original) {
        return io.github.lumi.client.integration.AxiomNativeReplayClient.current() != null ? null : original.call(history, mask);
    }

    @ModifyExpressionValue(method = {"undo(ZZZ)V", "redo(ZZ)V"}, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lcom/moulberry/axiom/clipboard/Placement;isPlacing()Z"))
    private static boolean lumi$admittedWorldReplay(boolean placing) {
        return placing && io.github.lumi.client.integration.AxiomNativeReplayClient.current() == null;
    }

    @WrapOperation(method = "undo(ZZZ)V", at = @At(value = "INVOKE",
            target = "Lcom/moulberry/axiom/world_modification/undo/AdditionalUndoOperation;perform()V"))
    private static void lumi$preserveNativeCallback(com.moulberry.axiom.world_modification.undo.AdditionalUndoOperation callback, Operation<Void> original) {
        if (io.github.lumi.client.integration.AxiomNativeReplayClient.current() != null) {
            if (callback instanceof com.moulberry.axiom.world_modification.undo.DeleteEntityAdditionalUndoOperation delete)
                callback = new com.moulberry.axiom.world_modification.undo.DeleteEntityAdditionalUndoOperation(new java.util.ArrayList<>(delete.entitiesToDelete()));
            else if (callback instanceof com.moulberry.axiom.world_modification.undo.MoveEntityAdditionalUndoOperation move)
                callback = new com.moulberry.axiom.world_modification.undo.MoveEntityAdditionalUndoOperation(new java.util.ArrayList<>(move.entitiesToMove()));
        }
        original.call(callback);
    }

    @Inject(method = "setHistoryPosition", at = @At("HEAD"), cancellable = true)
    private static void lumi$sharedHistoryPosition(int position, CallbackInfo callback) {
        if (AxiomWorldHistory.INSTANCE.moveTo(position)) callback.cancel();
    }

    @Inject(method = "clear", at = @At("HEAD"), cancellable = true)
    private static void lumi$sharedClear(CallbackInfo callback) {
        if (AxiomWorldHistory.INSTANCE.clear()) callback.cancel();
    }

    @WrapOperation(method = "applyPendingHistory", at = @At(value = "INVOKE", target = "Lcom/moulberry/axiom/world_modification/HistoryIO;loadHistory(Ljava/nio/file/Path;)Lcom/moulberry/axiom/world_modification/HistoryBuffer;"))
    private static HistoryBuffer<BlockOrBiomeBuffer> lumi$separateOldHistory(Path path, Operation<HistoryBuffer<BlockOrBiomeBuffer>> original) {
        return AxiomWorldHistory.INSTANCE.shared() ? new HistoryBuffer<>() : original.call(path);
    }

    @WrapOperation(method = "push", at = @At(value = "INVOKE", target = "Lcom/moulberry/axiom/world_modification/HistoryBuffer;push(Lcom/moulberry/axiom/world_modification/HistoryEntry;)V"))
    private static void lumi$serverOwnsWorldCursor(HistoryBuffer<BlockOrBiomeBuffer> history, HistoryEntry<BlockOrBiomeBuffer> entry, Operation<Void> original) {
        if (!AxiomWorldHistory.INSTANCE.shared()) original.call(history, entry);
    }

    @WrapOperation(method = "push", at = @At(value = "INVOKE", target = "Lcom/moulberry/axiom/world_modification/HistoryIO;pushEntry(Ljava/nio/file/Path;Lcom/moulberry/axiom/world_modification/HistoryEntry;II)V"))
    private static void lumi$preserveNativeFiles(Path path, HistoryEntry<BlockOrBiomeBuffer> entry, int position, int size, Operation<Void> original) {
        if (!AxiomWorldHistory.INSTANCE.shared()) original.call(path, entry, position, size);
    }

    @WrapOperation(method = "applyBlockBuffer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/IntegratedServer;submit(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;", remap = true))
    private static CompletableFuture<Void> lumi$fenceSubmission(
            IntegratedServer server, Runnable task, Operation<CompletableFuture<Void>> original, @Local(argsOnly = true) BlockBuffer buffer) {
        return lumi$submit(server, task, original, buffer);
    }

    @WrapOperation(method = "applyBiomeBuffer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/IntegratedServer;submit(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;", remap = true))
    private static CompletableFuture<Void> lumi$fenceBiomeSubmission(
            IntegratedServer server, Runnable task, Operation<CompletableFuture<Void>> original, @Local(argsOnly = true) BiomeBuffer buffer) {
        return lumi$submit(server, task, original, buffer);
    }

    private static CompletableFuture<Void> lumi$submit(IntegratedServer server, Runnable task,
            Operation<CompletableFuture<Void>> original, BlockOrBiomeBuffer buffer) {
        var client = Minecraft.getInstance();
        var entry = AxiomWorldHistory.INSTANCE.nativeEntry(buffer);
        var replay = io.github.lumi.client.integration.AxiomNativeReplayClient.current();
        if (replay != null) replay.retain();
        var runtime = LumiMod.serverRuntime().find(server.getLevel(client.level.dimension())).orElseThrow();
        long generation = runtime.freeze().replacementGeneration();
        long clientGeneration = AxiomClientWorkBarrier.generation();
        var actor = client.player == null ? null : client.player.getUUID();
        var future = original.call(server, (Runnable) () -> {
            var player = actor == null ? null : server.getPlayerList().getPlayer(actor);
            if (runtime.freeze().replacementGeneration() == generation
                    && AxiomClientWorkBarrier.generation() == clientGeneration
                    && player != null && player.level() == runtime.level()) {
                if (replay != null) { runtime.runNativeReplayRequest(player, replay.request(), task); return; }
                try (var ignored = io.github.lumi.minecraft.runtime.DirectLiveActionContext.open(runtime.liveActions(), actor)) {
                    if (entry == null) task.run();
                    else if (entry.epoch().equals(runtime.worldActionEpoch())) {
                        runtime.axiomBufferActions().begin(player.connection, actor, entry.entry(), java.util.Optional.of(
                                new io.github.lumi.domain.model.AxiomEntryIdentity(entry.epoch(), entry.entry())), false);
                        try { io.github.lumi.minecraft.runtime.AxiomBufferDispatchContext.run(player, entry.entry(), task); }
                        finally { runtime.axiomBufferActions().complete(player.connection, entry.entry()); }
                    }
                }
            }
        });
        if (replay != null) future.whenComplete((ignored, failure) -> { if (failure != null) replay.fail(); replay.release(); });
        return future;
    }
}
