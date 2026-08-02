package io.github.lumi.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(IOWorker.class)
public interface IOWorkerPersistenceAccessor {
    @Accessor("storage")
    RegionFileStorage lumi$storage();

    @Invoker("submitTask")
    <T> CompletableFuture<T> lumi$submitTask(Supplier<T> task);
}
