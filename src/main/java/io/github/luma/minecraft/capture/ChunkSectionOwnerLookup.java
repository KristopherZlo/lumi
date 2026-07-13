package io.github.luma.minecraft.capture;

import java.util.Optional;
import net.minecraft.world.level.chunk.LevelChunkSection;

@FunctionalInterface
interface ChunkSectionOwnerLookup {

    Optional<ChunkSectionOwnershipRegistry.SectionOwner> ownerOf(LevelChunkSection section);
}
