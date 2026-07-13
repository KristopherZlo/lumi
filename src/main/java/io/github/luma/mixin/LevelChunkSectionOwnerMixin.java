package io.github.luma.mixin;

import io.github.luma.minecraft.capture.ChunkSectionOwnerAccess;
import io.github.luma.minecraft.capture.ChunkSectionOwnershipRegistry;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LevelChunkSection.class)
abstract class LevelChunkSectionOwnerMixin implements ChunkSectionOwnerAccess {

    @Unique
    private volatile ChunkSectionOwnershipRegistry.SectionOwner luma$owner;

    @Override
    public ChunkSectionOwnershipRegistry.SectionOwner luma$getOwner() {
        return this.luma$owner;
    }

    @Override
    public void luma$setOwner(ChunkSectionOwnershipRegistry.SectionOwner owner) {
        this.luma$owner = owner;
    }
}
