package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.axiom.packets.AxiomServerboundFixArea;
import com.moulberry.axiom.packets.AxiomServerboundTickBlocks;
import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/** Gives explicit repair/tick effects one actor and preserves native permission/scope checks. */
@Mixin(value = {AxiomServerboundFixArea.class, AxiomServerboundTickBlocks.class}, remap = false)
abstract class AxiomRegionUpdateMixin {
    @WrapMethod(method = "handle")
    private void lumi$guardRegionUpdate(MinecraftServer server, ServerPlayer player, Operation<Void> original) {
        var runtime = LumiMod.serverRuntime().find(player.level())
                .orElseThrow(() -> new IllegalStateException("Lumi dimension runtime is not loaded"));
        if (!runtime.freeze().isMutationAllowed()) return;
        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player.getUUID())) {
            original.call(server, player);
        }
    }
}
