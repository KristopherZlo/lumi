package io.github.lumi.minecraft.world;

import io.github.lumi.mixin.ServerLevelEntityManagerAccessor;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** Holds a FULL chunk ticket until block and entity state are both available. */
public final class MinecraftChunkLoadAccess implements ChunkLoadAccess {
    private static final int RADIUS = 0;
    private static final TicketType LUMI_TICKET =
            new TicketType(200, TicketType.FLAG_LOADING);
    private final ServerLevel level;

    public MinecraftChunkLoadAccess(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public CompletableFuture<Void> retain(ChunkCoordinate chunk) {
        return level.getChunkSource().addTicketAndLoadWithRadius(
                LUMI_TICKET, position(chunk), RADIUS).thenApply(ignored -> null);
    }

    @Override
    public boolean isReady(ChunkCoordinate chunk) {
        ChunkPos position = position(chunk);
        return level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null
                && ((ServerLevelEntityManagerAccessor) level)
                        .lumi$entityManager().areEntitiesLoaded(position.toLong());
    }

    @Override
    public void release(ChunkCoordinate chunk) {
        level.getChunkSource().removeTicketWithRadius(
                LUMI_TICKET, position(chunk), RADIUS);
    }

    private static ChunkPos position(ChunkCoordinate chunk) {
        return new ChunkPos(chunk.x(), chunk.z());
    }
}
