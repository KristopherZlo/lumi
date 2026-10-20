package io.github.lumi.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.moulberry.axiom.world_modification.Dispatcher;
import com.moulberry.axiom.world_modification.HistoryBuffer;
import com.moulberry.axiom.world_modification.HistoryEntry;
import com.moulberry.axiom.world_modification.BlockOrBiomeBuffer;
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

    // Selection/placement cancellation has already run; stop before the world cursor moves.
    @Inject(method = "undo(ZZZ)V", at = @At(value = "FIELD", ordinal = 0,
            target = "Lcom/moulberry/axiom/world_modification/Dispatcher;history:Lcom/moulberry/axiom/world_modification/HistoryBuffer;"), cancellable = true)
    private static void lumi$guardWorldHistory(CallbackInfo callback) {
        if (AxiomClientWorkBarrier.isPaused() || AxiomWorldHistory.INSTANCE.step(true)) callback.cancel();
    }

    @Inject(method = "redo(ZZ)V", at = @At(value = "FIELD", ordinal = 0,
            target = "Lcom/moulberry/axiom/world_modification/Dispatcher;history:Lcom/moulberry/axiom/world_modification/HistoryBuffer;"), cancellable = true)
    private static void lumi$sharedRedo(CallbackInfo callback) {
        if (AxiomClientWorkBarrier.isPaused() || AxiomWorldHistory.INSTANCE.step(false)) callback.cancel();
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

    @WrapOperation(method = {"applyBlockBuffer", "applyBiomeBuffer"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/IntegratedServer;submit(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;", remap = true))
    private static CompletableFuture<Void> lumi$fenceSubmission(
            IntegratedServer server, Runnable task, Operation<CompletableFuture<Void>> original) {
        var client = Minecraft.getInstance();
        var runtime = LumiMod.serverRuntime().find(server.getLevel(client.level.dimension())).orElseThrow();
        long generation = runtime.freeze().replacementGeneration();
        long clientGeneration = AxiomClientWorkBarrier.generation();
        var actor = client.player == null ? null : client.player.getUUID();
        return original.call(server, (Runnable) () -> {
            var player = actor == null ? null : server.getPlayerList().getPlayer(actor);
            if (runtime.freeze().replacementGeneration() == generation
                    && AxiomClientWorkBarrier.generation() == clientGeneration
                    && player != null && player.level() == runtime.level()) {
                try (var ignored = io.github.lumi.minecraft.runtime.DirectLiveActionContext.open(runtime.liveActions(), actor)) {
                    task.run();
                }
            }
        });
    }
}
