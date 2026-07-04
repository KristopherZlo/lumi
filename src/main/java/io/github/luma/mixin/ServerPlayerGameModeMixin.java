package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin {

    @Shadow
    protected ServerPlayer player;

    @Unique
    private int luma$playerMutationDepth = 0;

    @WrapMethod(method = "destroyBlock")
    private boolean luma$wrapDestroyBlock(BlockPos pos, Operation<Boolean> original) {
        this.luma$pushPlayerSource();
        try {
            return original.call(pos);
        } finally {
            this.luma$popPlayerSource();
        }
    }

    @WrapMethod(method = "useItem")
    private InteractionResult luma$wrapUseItem(
            ServerPlayer player,
            Level level,
            ItemStack stack,
            InteractionHand hand,
            Operation<InteractionResult> original
    ) {
        this.luma$pushPlayerSource();
        try {
            return original.call(player, level, stack, hand);
        } finally {
            this.luma$popPlayerSource();
        }
    }

    @WrapMethod(method = "useItemOn")
    private InteractionResult luma$wrapUseItemOn(
            ServerPlayer player,
            Level level,
            ItemStack stack,
            InteractionHand hand,
            BlockHitResult hitResult,
            Operation<InteractionResult> original
    ) {
        this.luma$pushPlayerSource();
        try {
            return original.call(player, level, stack, hand, hitResult);
        } finally {
            this.luma$popPlayerSource();
        }
    }

    @Unique
    private void luma$pushPlayerSource() {
        this.luma$playerMutationDepth += 1;
        LumaAccessControl accessControl = LumaAccessControl.getInstance();
        WorldMutationContext.pushPlayerSource(
                WorldMutationSource.PLAYER,
                this.player == null ? "player" : this.player.getName().getString(),
                accessControl.canUse(this.player) || WorldMutationContext.currentAccessAllowed(),
                accessControl.survivalMode(this.player) || WorldMutationContext.currentSurvivalMode()
        );
    }

    @Unique
    private void luma$popPlayerSource() {
        if (this.luma$playerMutationDepth <= 0) {
            return;
        }

        this.luma$playerMutationDepth -= 1;
        WorldMutationContext.popSource();
    }
}
