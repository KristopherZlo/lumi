package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.DeferredWorldMutationContexts;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ServerLevel.class)
abstract class ServerLevelBlockEventContextMixin {

    @ModifyArg(
            method = "blockEvent",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;add(Ljava/lang/Object;)Z",
                    remap = false
            )
    )
    private Object luma$rememberBlockEventContext(Object event) {
        DeferredWorldMutationContexts.remember(event, WorldMutationSource.BLOCK_UPDATE);
        return event;
    }

    @WrapOperation(
            method = "doBlockEvent",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;triggerEvent(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;II)Z"
            )
    )
    private boolean luma$wrapBlockEventContext(
            BlockState state,
            Level level,
            BlockPos pos,
            int eventId,
            int eventParam,
            Operation<Boolean> original,
            BlockEventData event
    ) {
        DeferredWorldMutationContexts.push(event);
        try {
            return original.call(state, level, pos, eventId, eventParam);
        } finally {
            DeferredWorldMutationContexts.pop();
        }
    }
}
