package io.github.luma.minecraft.capture;

public interface ChunkSectionOwnerAccess {

    ChunkSectionOwnershipRegistry.SectionOwner luma$getOwner();

    void luma$setOwner(ChunkSectionOwnershipRegistry.SectionOwner owner);
}
