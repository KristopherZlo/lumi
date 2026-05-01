package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

final class ServerLevelChunkPreloadAccess implements ChunkPreloadAccess {

    private static final int TICKET_RADIUS = 0;

    private final ServerLevel level;

    ServerLevelChunkPreloadAccess(ServerLevel level) {
        this.level = level;
    }

    @Override
    public boolean isLoaded(ChunkPoint chunk) {
        if (this.level == null || chunk == null) {
            return false;
        }
        return this.level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null;
    }

    @Override
    public boolean load(ChunkPoint chunk) {
        if (this.level == null || chunk == null) {
            return false;
        }
        return this.level.getChunk(chunk.x(), chunk.z()) != null;
    }

    @Override
    public void acquireTicket(ChunkPoint chunk) {
        if (this.level == null || chunk == null) {
            return;
        }
        this.level.getChunkSource().addTicketWithRadius(
                TicketType.UNKNOWN,
                new ChunkPos(chunk.x(), chunk.z()),
                TICKET_RADIUS
        );
    }

    @Override
    public void releaseTicket(ChunkPoint chunk) {
        if (this.level == null || chunk == null) {
            return;
        }
        this.level.getChunkSource().removeTicketWithRadius(
                TicketType.UNKNOWN,
                new ChunkPos(chunk.x(), chunk.z()),
                TICKET_RADIUS
        );
    }
}
