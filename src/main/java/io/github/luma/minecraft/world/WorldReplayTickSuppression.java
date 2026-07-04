package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Temporarily ignores stale block callbacks left behind by exact history replay.
 */
public final class WorldReplayTickSuppression {

    private static final WorldReplayTickSuppression INSTANCE = new WorldReplayTickSuppression();

    private final Map<ServerLevel, Long2LongLinkedOpenHashMap> protectedPositions = new IdentityHashMap<>();
    private final Map<ServerLevel, Integer> frozenWorldTicks = new IdentityHashMap<>();

    private WorldReplayTickSuppression() {
    }

    public static WorldReplayTickSuppression getInstance() {
        return INSTANCE;
    }

    public synchronized void protect(ServerLevel level, Collection<BlockPos> positions, int ticks) {
        if (level == null || positions == null || positions.isEmpty() || ticks <= 0) {
            return;
        }

        long expiresAt = level.getGameTime() + ticks;
        Long2LongLinkedOpenHashMap worldPositions = this.protectedPositions.computeIfAbsent(
                level,
                ignored -> new Long2LongLinkedOpenHashMap()
        );
        for (BlockPos pos : positions) {
            if (pos != null) {
                worldPositions.put(pos.asLong(), expiresAt);
            }
        }
        this.removeExpired(level, worldPositions, level.getGameTime());
    }

    public synchronized void clear(ServerLevel level) {
        if (level == null) {
            return;
        }
        this.protectedPositions.remove(level);
    }

    public synchronized void freezeWorldTick(ServerLevel level) {
        if (level == null) {
            return;
        }
        this.frozenWorldTicks.merge(level, 1, Integer::sum);
    }

    public synchronized void releaseWorldTickFreeze(ServerLevel level) {
        if (level == null) {
            return;
        }
        Integer count = this.frozenWorldTicks.get(level);
        if (count == null || count <= 1) {
            this.frozenWorldTicks.remove(level);
            return;
        }
        this.frozenWorldTicks.put(level, count - 1);
    }

    public synchronized boolean shouldFreezeWorldTick(ServerLevel level) {
        return level != null && this.frozenWorldTicks.containsKey(level);
    }

    public synchronized boolean shouldSuppress(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }

        Long2LongLinkedOpenHashMap worldPositions = this.protectedPositions.get(level);
        if (worldPositions == null || worldPositions.isEmpty()) {
            return false;
        }

        long now = level.getGameTime();
        this.removeExpired(level, worldPositions, now);
        long expiresAt = worldPositions.getOrDefault(pos.asLong(), Long.MIN_VALUE);
        return expiresAt >= now;
    }

    public boolean shouldSuppressCallback(ServerLevel level, BlockPos pos) {
        if (!this.isReplayCallbackSource(WorldMutationContext.currentSource())) {
            return false;
        }
        return this.shouldSuppress(level, pos);
    }

    public boolean shouldSuppressMutation(ServerLevel level, BlockPos pos, WorldMutationSource source) {
        return (source == WorldMutationSource.BLOCK_UPDATE
                || source == WorldMutationSource.PISTON
                || source == WorldMutationSource.FLUID)
                && this.shouldSuppress(level, pos);
    }

    boolean isReplayCallbackSource(WorldMutationSource source) {
        if (source == null) {
            return true;
        }
        return switch (source) {
            case BLOCK_UPDATE, PISTON, RESTORE, SYSTEM, FLUID -> true;
            case PLAYER, ENTITY, EXPLOSION, FIRE, GROWTH, FALLING_BLOCK, EXPLOSIVE,
                    MOB, EXTERNAL_TOOL, WORLDEDIT, FAWE, AXIOM -> false;
        };
    }

    private void removeExpired(ServerLevel level, Long2LongLinkedOpenHashMap worldPositions, long now) {
        Iterator<Long2LongMap.Entry> iterator = worldPositions.long2LongEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Long2LongMap.Entry entry = iterator.next();
            if (entry.getLongValue() < now) {
                iterator.remove();
            }
        }
        if (worldPositions.isEmpty()) {
            this.protectedPositions.remove(level);
        }
    }
}
