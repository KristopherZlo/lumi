package io.github.lumi.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.axiom.world_modification.Dispatcher;
import io.github.lumi.LumiMod;
import io.github.lumi.client.integration.AxiomClientWorkBarrier;
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
        if (AxiomClientWorkBarrier.isPaused()) callback.cancel();
    }

    // Selection/placement cancellation has already run; stop before the world cursor moves.
    @Inject(method = {"undo(ZZZ)V", "redo(ZZ)V"}, at = @At(value = "FIELD", ordinal = 0,
            target = "Lcom/moulberry/axiom/world_modification/Dispatcher;history:Lcom/moulberry/axiom/world_modification/HistoryBuffer;"), cancellable = true)
    private static void lumi$guardWorldHistory(CallbackInfo callback) {
        if (AxiomClientWorkBarrier.isPaused()) callback.cancel();
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
