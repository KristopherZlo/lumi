package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.BlockEntityMutationSnapshotRegistry;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockEntity.class)
abstract class BlockEntitySetChangedMixin {

    @Unique
    private static final BlockEntityMutationSnapshotRegistry LUMA_BLOCK_ENTITY_SNAPSHOTS =
            BlockEntityMutationSnapshotRegistry.getInstance();

    @WrapMethod(method = "setChanged()V")
    private void luma$wrapSetChanged(Operation<Void> original) {
        original.call();
        LUMA_BLOCK_ENTITY_SNAPSHOTS.recordIfChanged((BlockEntity) (Object) this);
    }
}
