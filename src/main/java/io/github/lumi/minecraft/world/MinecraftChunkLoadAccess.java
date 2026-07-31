package io.github.lumi.minecraft.world;

import io.github.lumi.mixin.ServerChunkCacheAccessor;
import io.github.lumi.mixin.ServerLevelEntityManagerAccessor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Holds a FULL chunk ticket until the requested terrain/entity state is available. */
public final class MinecraftChunkLoadAccess implements ChunkLoadAccess {
    private static final int MAX_PENDING = 32;
    private static final TicketType LUMI_TICKET =
            new TicketType(
                    TicketType.NO_TIMEOUT,
                    TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private final ServerLevel level;
    private final DimensionFreezeState freeze;
    private final Readiness readiness;
    private final int ticketRadius;
    private final Map<ChunkCoordinate, CompletableFuture<Void>> pending =
            new LinkedHashMap<>();

    public MinecraftChunkLoadAccess(ServerLevel level, DimensionFreezeState freeze) {
        this(level, freeze, Readiness.TERRAIN_AND_ENTITIES);
    }

    MinecraftChunkLoadAccess(
            ServerLevel level, DimensionFreezeState freeze, Readiness readiness) {
        this.level = Objects.requireNonNull(level, "level");
        this.freeze = Objects.requireNonNull(freeze, "freeze");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        ticketRadius = readiness == Readiness.TERRAIN ? 1 : 0;
    }

    @Override
    public CompletableFuture<Void> retain(ChunkCoordinate chunk) {
        boolean ready = terrainReady(chunk);
        level.getChunkSource().addTicketWithRadius(
                LUMI_TICKET, position(chunk), ticketRadius);
        if (ready) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> loading = new CompletableFuture<>();
        pending.put(chunk, loading);
        if (pending.size() == MAX_PENDING) {
            try {
                startLoading();
            } catch (RuntimeException failed) {
                release(chunk);
                throw failed;
            }
        }
        return loading;
    }

    @Override
    public void startLoading() {
        if (pending.isEmpty()) {
            return;
        }
        var chunks = level.getChunkSource();
        var access = (ServerChunkCacheAccessor) chunks;
        access.lumi$runDistanceManagerUpdates();
        pending.forEach((chunk, loading) -> {
            var holder = chunks.chunkMap.getUpdatingChunkIfPresent(
                    position(chunk).toLong());
            if (holder == null) {
                loading.completeExceptionally(
                        new IllegalStateException("No chunk was scheduled for loading"));
                return;
            }
            holder.scheduleChunkGenerationTask(ChunkStatus.FULL, chunks.chunkMap)
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) {
                            loading.complete(null);
                        } else {
                            loading.completeExceptionally(failure);
                        }
                    });
        });
        pending.clear();
    }

    @Override
    public boolean isReady(ChunkCoordinate chunk) {
        if (readiness == Readiness.TERRAIN) {
            return terrainReady(chunk);
        }
        ChunkPos position = position(chunk);
        var entities = ((ServerLevelEntityManagerAccessor) level).lumi$entityManager();
        freeze.runAuthorizedEntityAddition(entities::processPendingLoads);
        return terrainReady(chunk) && entities.areEntitiesLoaded(position.toLong());
    }

    @Override
    public void release(ChunkCoordinate chunk) {
        pending.remove(chunk);
        level.getChunkSource().removeTicketWithRadius(
                LUMI_TICKET, position(chunk), ticketRadius);
    }

    private static ChunkPos position(ChunkCoordinate chunk) {
        return new ChunkPos(chunk.x(), chunk.z());
    }

    private boolean terrainReady(ChunkCoordinate chunk) {
        return level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null;
    }
}
