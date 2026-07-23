package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.axiom.packets.AxiomServerboundSetBlock;
import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/** Makes one Axiom fast-place request one durable, live-undoable action. */
@Mixin(value = AxiomServerboundSetBlock.class, remap = false)
abstract class AxiomSetBlockMixin {
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
        try (var ignored = DirectLiveActionContext.open(
                runtime.liveActions(), player.getUUID())) {
            original.call(server, player);
        }
    }
}
