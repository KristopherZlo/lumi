package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.BlockEntityMutationSnapshotRegistry;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LecternBlockEntity.class)
abstract class LecternBlockEntityMixin {

    @Unique
    private static final BlockEntityMutationSnapshotRegistry LUMA_BLOCK_ENTITY_SNAPSHOTS =
            BlockEntityMutationSnapshotRegistry.getInstance();

    @WrapMethod(method = "setPage")
    private void luma$wrapSetPage(int page, Operation<Void> original) {
        LecternBlockEntity lectern = (LecternBlockEntity) (Object) this;
        LUMA_BLOCK_ENTITY_SNAPSHOTS.captureBeforePotentialMutation(lectern);
        original.call(page);
        LUMA_BLOCK_ENTITY_SNAPSHOTS.recordIfChanged(lectern);
    }
}
