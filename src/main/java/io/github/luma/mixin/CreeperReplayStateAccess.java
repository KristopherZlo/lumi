package io.github.luma.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Creeper.class)
public interface CreeperReplayStateAccess {

    @Accessor("swell")
    void luma$setSwell(int swell);

    @Accessor("oldSwell")
    void luma$setOldSwell(int oldSwell);

    @Accessor("DATA_IS_IGNITED")
    static EntityDataAccessor<Boolean> luma$dataIsIgnited() {
        throw new AssertionError();
    }
}
