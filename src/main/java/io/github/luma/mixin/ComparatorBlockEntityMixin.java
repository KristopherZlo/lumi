package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.BlockEntityMutationSnapshotRegistry;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ComparatorBlockEntity.class)
abstract class ComparatorBlockEntityMixin {

    @Unique
    private static final BlockEntityMutationSnapshotRegistry LUMA_BLOCK_ENTITY_SNAPSHOTS =
            BlockEntityMutationSnapshotRegistry.getInstance();

    @WrapMethod(method = "setOutputSignal")
    private void luma$wrapSetOutputSignal(int output, Operation<Void> original) {
        ComparatorBlockEntity comparator = (ComparatorBlockEntity) (Object) this;
        LUMA_BLOCK_ENTITY_SNAPSHOTS.captureBeforePotentialMutation(comparator);
        original.call(output);
        LUMA_BLOCK_ENTITY_SNAPSHOTS.recordIfChanged(comparator);
    }
}
