package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.BlockEntityMutationSnapshotRegistry;
import net.minecraft.world.level.block.entity.SculkSensorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SculkSensorBlockEntity.class)
abstract class SculkSensorBlockEntityMixin {

    @Unique
    private static final BlockEntityMutationSnapshotRegistry LUMA_BLOCK_ENTITY_SNAPSHOTS =
            BlockEntityMutationSnapshotRegistry.getInstance();

    @WrapMethod(method = "setLastVibrationFrequency")
    private void luma$wrapSetLastVibrationFrequency(int frequency, Operation<Void> original) {
        SculkSensorBlockEntity sensor = (SculkSensorBlockEntity) (Object) this;
        LUMA_BLOCK_ENTITY_SNAPSHOTS.captureBeforePotentialMutation(sensor);
        original.call(frequency);
        LUMA_BLOCK_ENTITY_SNAPSHOTS.recordIfChanged(sensor);
    }
}
