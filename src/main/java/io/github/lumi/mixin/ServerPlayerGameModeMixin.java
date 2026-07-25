package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin {
    @Shadow protected ServerLevel level;
    @Shadow @Final protected ServerPlayer player;

    @WrapMethod(method = "destroyBlock")
    private boolean lumi$trackDestroy(BlockPos position, Operation<Boolean> original) {
        return lumi$track(() -> original.call(position));
    }

    @WrapMethod(method = "useItem")
    private InteractionResult lumi$trackUse(
            ServerPlayer actor, Level world, ItemStack stack, InteractionHand hand,
            Operation<InteractionResult> original) {
        return lumi$track(() -> original.call(actor, world, stack, hand));
    }

    @WrapMethod(method = "useItemOn")
    private InteractionResult lumi$trackUseOn(
            ServerPlayer actor, Level world, ItemStack stack, InteractionHand hand,
            BlockHitResult hit, Operation<InteractionResult> original) {
        return lumi$track(() -> original.call(actor, world, stack, hand, hit));
    }

    @Unique
    private <T> T lumi$track(Supplier<T> original) {
        var runtime = LumiMod.serverRuntime().find(level).orElse(null);
        if (runtime == null) {
            return original.get();
        }
        try (var ignored = DirectLiveActionContext.open(
                runtime.liveActions(), player.getUUID())) {
            return original.get();
        }
    }
}
