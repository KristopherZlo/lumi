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
            new TicketType(
                    TicketType.NO_TIMEOUT,
                    TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private final ServerLevel level;
    private final Readiness readiness;

    public MinecraftChunkLoadAccess(ServerLevel level) {
        this(level, Readiness.TERRAIN_AND_ENTITIES);
    }

    MinecraftChunkLoadAccess(ServerLevel level, Readiness readiness) {
        this.level = Objects.requireNonNull(level, "level");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    @Override
    public CompletableFuture<Void> retain(ChunkCoordinate chunk) {
        ChunkPos position = position(chunk);
        if (terrainReady(chunk)) {
            level.getChunkSource().addTicketWithRadius(LUMI_TICKET, position, RADIUS);
            return CompletableFuture.completedFuture(null);
        }
        return level.getChunkSource().addTicketAndLoadWithRadius(
                LUMI_TICKET, position, RADIUS).thenApply(ignored -> null);
    }

    @Override
    public boolean isReady(ChunkCoordinate chunk) {
        if (readiness == Readiness.TERRAIN) {
            return terrainReady(chunk);
        }
        ChunkPos position = position(chunk);
        var entities = ((ServerLevelEntityManagerAccessor) level).lumi$entityManager();
        entities.processPendingLoads();
        return terrainReady(chunk) && entities.areEntitiesLoaded(position.toLong());
    }

    @Override
    public void release(ChunkCoordinate chunk) {
        level.getChunkSource().removeTicketWithRadius(
                LUMI_TICKET, position(chunk), RADIUS);
    }

    private static ChunkPos position(ChunkCoordinate chunk) {
        return new ChunkPos(chunk.x(), chunk.z());
    }

    private boolean terrainReady(ChunkCoordinate chunk) {
        return level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null;
    }
}
