package io.github.luma.minecraft.capture;

import java.util.Optional;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Resolves server ownership for chunk sections that may be mutated directly by
 * external builder tools.
 */
@FunctionalInterface
public interface ChunkSectionOwnerLookup {

    Optional<ChunkSectionOwnershipRegistry.SectionOwner> ownerOf(LevelChunkSection section);
}
