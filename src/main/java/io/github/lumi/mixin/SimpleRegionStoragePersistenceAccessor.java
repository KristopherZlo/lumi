package io.github.lumi.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SimpleRegionStorage.class)
public interface SimpleRegionStoragePersistenceAccessor {
    @Accessor("worker")
    IOWorker lumi$worker();
}
