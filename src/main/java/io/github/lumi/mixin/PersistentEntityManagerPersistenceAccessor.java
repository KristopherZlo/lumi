package io.github.lumi.mixin;

import java.util.function.Consumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PersistentEntitySectionManager.class)
public interface PersistentEntityManagerPersistenceAccessor<T extends EntityAccess> {
    @Invoker("storeChunkSections")
    boolean lumi$storeChunkSections(long packedChunk, Consumer<T> afterStore);

    @Accessor("permanentStorage")
    EntityPersistentStorage<T> lumi$permanentStorage();

    @Accessor("sectionStorage")
    EntitySectionStorage<T> lumi$sectionStorage();
}
