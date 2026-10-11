package io.github.lumi.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.lumi.gametest.LumiInteractionTiming;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/** Wraps the production interaction wrappers only in the GameTest mod. */
@Mixin(value = ServerPlayerGameMode.class, priority = 900)
public abstract class InteractionTimingMixin {
    @WrapMethod(method = "destroyBlock")
    private boolean lumi$measureBreak(BlockPos position, Operation<Boolean> original) {
        long started = System.nanoTime();
        try { return original.call(position); }
        finally { LumiInteractionTiming.record(started); }
    }

    @WrapMethod(method = "useItemOn")
    private InteractionResult lumi$measureUse(
            ServerPlayer player, Level level, ItemStack stack,
            InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
        long started = System.nanoTime();
        try { return original.call(player, level, stack, hand, hit); }
        finally { LumiInteractionTiming.record(started); }
    }
}
