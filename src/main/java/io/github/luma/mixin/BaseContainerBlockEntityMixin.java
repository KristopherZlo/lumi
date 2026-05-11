package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.BlockEntityMutationSnapshotRegistry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BaseContainerBlockEntity.class)
abstract class BaseContainerBlockEntityMixin {

    @Unique
    private static final BlockEntityMutationSnapshotRegistry LUMA_BLOCK_ENTITY_SNAPSHOTS =
            BlockEntityMutationSnapshotRegistry.getInstance();

    @WrapMethod(method = "getItem")
    private ItemStack luma$wrapGetItem(int slot, Operation<ItemStack> original) {
        this.luma$captureBeforePotentialMutation();
        return original.call(slot);
    }

    @WrapMethod(method = "removeItem")
    private ItemStack luma$wrapRemoveItem(int slot, int count, Operation<ItemStack> original) {
        this.luma$captureBeforePotentialMutation();
        ItemStack result = original.call(slot, count);
        this.luma$recordIfChanged();
        return result;
    }

    @WrapMethod(method = "removeItemNoUpdate")
    private ItemStack luma$wrapRemoveItemNoUpdate(int slot, Operation<ItemStack> original) {
        this.luma$captureBeforePotentialMutation();
        ItemStack result = original.call(slot);
        this.luma$recordIfChanged();
        return result;
    }

    @WrapMethod(method = "setItem")
    private void luma$wrapSetItem(int slot, ItemStack stack, Operation<Void> original) {
        this.luma$captureBeforePotentialMutation();
        original.call(slot, stack);
        this.luma$recordIfChanged();
    }

    @WrapMethod(method = "clearContent")
    private void luma$wrapClearContent(Operation<Void> original) {
        this.luma$captureBeforePotentialMutation();
        original.call();
        this.luma$recordIfChanged();
    }

    @Unique
    private void luma$captureBeforePotentialMutation() {
        LUMA_BLOCK_ENTITY_SNAPSHOTS.captureBeforePotentialMutation((BaseContainerBlockEntity) (Object) this);
    }

    @Unique
    private void luma$recordIfChanged() {
        LUMA_BLOCK_ENTITY_SNAPSHOTS.recordIfChanged((BaseContainerBlockEntity) (Object) this);
    }
}
