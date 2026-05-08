package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class ServerChunkSkylightRefreshAccess implements ChunkSkylightRefreshQueue.RefreshAccess {

    private final ServerLevel level;

    ServerChunkSkylightRefreshAccess(ServerLevel level) {
        this.level = level;
    }

    @Override
    public boolean refreshSectionStatus(SectionPos sectionPos) {
        if (this.level == null || sectionPos == null) {
            return false;
        }
        LevelChunk chunk = this.level.getChunkSource().getChunkNow(sectionPos.x(), sectionPos.z());
        if (chunk == null) {
            return false;
        }
        int sectionIndex = chunk.getSectionIndexFromSectionY(sectionPos.y());
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            return false;
        }
        LevelChunkSection section = chunk.getSection(sectionIndex);
        if (section == null) {
            return false;
        }
        boolean empty = section.hasOnlyAir();
        this.level.getChunkSource().getLightEngine().updateSectionStatus(sectionPos, empty);
        this.level.getChunkSource().onSectionEmptinessChanged(sectionPos.x(), sectionPos.y(), sectionPos.z(), empty);
        return true;
    }

    @Override
    public boolean refreshChunkSkySources(ChunkPoint chunkPoint) {
        if (this.level == null || chunkPoint == null) {
            return false;
        }
        LevelChunk chunk = this.level.getChunkSource().getChunkNow(chunkPoint.x(), chunkPoint.z());
        if (chunk == null) {
            return false;
        }
        chunk.initializeLightSources();
        chunk.markUnsaved();
        return true;
    }
}
