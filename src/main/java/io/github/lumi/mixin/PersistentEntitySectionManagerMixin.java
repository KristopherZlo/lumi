package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.EntityStorageLevelAccess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PersistentEntitySectionManager.class)
abstract class PersistentEntitySectionManagerMixin<T extends EntityAccess> {
    @Shadow @Final private EntityPersistentStorage<T> permanentStorage;
    @Shadow @Final private EntitySectionStorage<T> sectionStorage;

    @Inject(
            method = "storeChunkSections",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/world/level/entity/EntityPersistentStorage;storeEntities"
                            + "(Lnet/minecraft/world/level/entity/ChunkEntities;)V"),
            cancellable = true)
    private void lumi$gateChangedEntityStore(
            long packedChunk, Consumer<T> afterStore,
            CallbackInfoReturnable<Boolean> callback) {
        var runtime = lumi$runtime();
        if (runtime == null) {
            return;
        }
        try {
            var entities = sectionStorage.getExistingSectionsInChunk(packedChunk)
                    .flatMap(section -> section.getEntities());
            if (!runtime.permitEntityStore(new ChunkPos(packedChunk), entities)) {
                callback.setReturnValue(false);
            }
        } catch (IOException failed) {
            throw new UncheckedIOException("Cannot capture Lumi entity chunk", failed);
        }
    }

    @Inject(method = "processChunkUnload", at = @At("RETURN"))
    private void lumi$discardUnloadedBaseline(
            long packedChunk, CallbackInfoReturnable<Boolean> callback) {
        var runtime = lumi$runtime();
        if (runtime != null && callback.getReturnValueZ()) {
            runtime.entityChunkUnloaded(new ChunkPos(packedChunk));
        }
    }

    private io.github.lumi.minecraft.runtime.FabricDimensionRuntime lumi$runtime() {
        if (!(permanentStorage instanceof EntityStorageLevelAccess storage)) {
            return null;
        }
        return LumiMod.serverRuntime().find(storage.lumi$level()).orElse(null);
    }
}
