package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.BlockEntityMutationSnapshotRegistry;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LecternBlockEntity.class)
abstract class LecternBlockEntityMixin {

    @Unique
    private static final BlockEntityMutationSnapshotRegistry LUMA_BLOCK_ENTITY_SNAPSHOTS =
            BlockEntityMutationSnapshotRegistry.getInstance();

    @WrapMethod(method = "setBook(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)V")
    private void luma$wrapSetBook(ItemStack book, Player player, Operation<Void> original) {
        LecternBlockEntity lectern = (LecternBlockEntity) (Object) this;
        LUMA_BLOCK_ENTITY_SNAPSHOTS.captureBeforePotentialMutation(lectern);
        original.call(book, player);
        LUMA_BLOCK_ENTITY_SNAPSHOTS.recordIfChanged(lectern);
    }

    @WrapMethod(method = "setPage")
    private void luma$wrapSetPage(int page, Operation<Void> original) {
        LecternBlockEntity lectern = (LecternBlockEntity) (Object) this;
        LUMA_BLOCK_ENTITY_SNAPSHOTS.captureBeforePotentialMutation(lectern);
        original.call(page);
        LUMA_BLOCK_ENTITY_SNAPSHOTS.recordIfChanged(lectern);
    }
}
