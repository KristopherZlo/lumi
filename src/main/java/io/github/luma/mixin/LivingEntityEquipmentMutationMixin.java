package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.EntityMutationTracker;
import io.github.luma.minecraft.capture.EntityMutationTracker.PendingEntityMutation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LivingEntity.class)
abstract class LivingEntityEquipmentMutationMixin {

    @WrapMethod(method = "setItemSlot")
    private void luma$wrapSetItemSlot(EquipmentSlot slot, ItemStack stack, Operation<Void> original) {
        LivingEntity entity = (LivingEntity) (Object) this;
        PendingEntityMutation pending = EntityMutationTracker.captureBefore(entity);
        original.call(slot, stack);
        EntityMutationTracker.captureAfter(entity, pending);
    }
}
