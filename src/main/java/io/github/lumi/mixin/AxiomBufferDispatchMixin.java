package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import io.github.lumi.minecraft.runtime.AxiomBufferDispatch;
import io.github.lumi.minecraft.runtime.AxiomBufferDispatchContext;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = AxiomServerboundSetBuffer.class, remap = false)
abstract class AxiomBufferDispatchMixin implements AxiomBufferDispatch {
    @Shadow @Final private ResourceKey<Level> world;
    @Unique private UUID lumi$dispatch;

    @Override public void lumi$dispatch(UUID dispatch) { lumi$dispatch = dispatch; }

    @WrapOperation(method = "read", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/FriendlyByteBuf;readUUID()Ljava/util/UUID;", remap = true))
    private static UUID lumi$readDispatch(FriendlyByteBuf buffer, Operation<UUID> original,
            @Share("dispatch") LocalRef<UUID> dispatch) {
        UUID value = original.call(buffer);
        dispatch.set(value);
        return value;
    }

    @ModifyReturnValue(method = "read", at = @At("RETURN"))
    private static AxiomServerboundSetBuffer lumi$keepDispatch(AxiomServerboundSetBuffer packet,
            @Share("dispatch") LocalRef<UUID> dispatch) {
        ((AxiomBufferDispatch) packet).lumi$dispatch(dispatch.get());
        return packet;
    }

    @WrapMethod(method = "handle")
    private void lumi$dispatchScope(MinecraftServer server, ServerPlayer player, Operation<Void> original) {
        if (!world.equals(player.level().dimension())) return;
        var runtime = io.github.lumi.LumiMod.serverRuntime().find(player.level()).orElseThrow();
        if (lumi$dispatch != null && runtime.runNativeReplay(player, lumi$dispatch, () -> original.call(server, player))) return;
        if (lumi$dispatch == null) original.call(server, player);
        else if (server.getPlayerList().getPlayer(player.getUUID()) == player) {
            AxiomBufferDispatchContext.run(player, lumi$dispatch, () -> original.call(server, player));
        }
    }
}
