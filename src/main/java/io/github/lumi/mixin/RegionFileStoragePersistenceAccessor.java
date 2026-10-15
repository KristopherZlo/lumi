package io.github.lumi.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RegionFileStorage.class)
public interface RegionFileStoragePersistenceAccessor {
    //? if >=26.3 {
    /*@Accessor("regionCache")
    Long2ObjectLinkedOpenHashMap<java.util.Optional<RegionFile>> lumi$regionCache();
    *///?} else {
    @Accessor("regionCache")
    Long2ObjectLinkedOpenHashMap<RegionFile> lumi$regionCache();
    //?}
}
