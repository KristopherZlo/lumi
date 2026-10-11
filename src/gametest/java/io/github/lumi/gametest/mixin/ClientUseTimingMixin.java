package io.github.lumi.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.lumi.gametest.LumiInteractionTiming;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = MultiPlayerGameMode.class, priority = 500)
public abstract class ClientUseTimingMixin {
    @WrapMethod(method = "useItemOn")
    private InteractionResult lumi$observeUse(LocalPlayer player, InteractionHand hand,
            BlockHitResult hit, Operation<InteractionResult> original) {
        LumiInteractionTiming.recordClientUse();
        InteractionResult result = original.call(player, hand, hit);
        LumiInteractionTiming.recordClientResult(result);
        return result;
    }
}
