package io.github.luma.minecraft.capture;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ChunkSectionOwnershipRegistry {

    private static final ChunkSectionOwnershipRegistry INSTANCE = new ChunkSectionOwnershipRegistry();

    private final Map<LevelChunkSection, SectionOwner> owners = Collections.synchronizedMap(new WeakHashMap<>());

    public static ChunkSectionOwnershipRegistry getInstance() {
        return INSTANCE;
    }

    public void register(ChunkAccess chunk, LevelChunkSection[] sections) {
        if (sections == null) {
            return;
        }
        for (int index = 0; index < sections.length; index++) {
            this.register(chunk, index, sections[index]);
        }
    }

    public void register(ChunkAccess chunk, int sectionIndex, LevelChunkSection section) {
        if (!(chunk instanceof LevelChunk levelChunk) || !(levelChunk.getLevel() instanceof ServerLevel serverLevel) || section == null) {
            return;
        }

        this.owners.put(section, new SectionOwner(
                serverLevel,
                chunk.getPos(),
                chunk.getSectionYFromSectionIndex(sectionIndex)
        ));
    }

    public Optional<SectionOwner> ownerOf(LevelChunkSection section) {
        if (section == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.owners.get(section));
    }

    public record SectionOwner(
            ServerLevel level,
            ChunkPos chunkPos,
            int sectionY
    ) {

        public BlockPos blockPos(int localX, int localY, int localZ) {
            return new BlockPos(
                    this.chunkPos.getBlockX(localX),
                    (this.sectionY << 4) + localY,
                    this.chunkPos.getBlockZ(localZ)
            );
        }
    }
}
