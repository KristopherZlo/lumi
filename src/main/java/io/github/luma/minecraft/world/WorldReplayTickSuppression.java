package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import io.github.luma.debug.LumaDiagnosticsLog;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.debug.LumaLoadLog;
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
        if (LumaDiagnosticsLog.fluidUndoEnabled()) {
            LumaDiagnosticsLog.fluidUndoEvent("replay-protect",
                    "dimension=" + level.dimension().identifier()
                            + ", time=" + level.getGameTime()
                            + ", ticks=" + ticks
                            + ", positions=" + positions.size()
                            + ", sample=[" + samplePositions(positions) + "]");
        }
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
        int count = this.frozenWorldTicks.merge(level, 1, Integer::sum);
        LumaLoadLog.event("tnt-replay", "freeze-acquire",
                "dimension=" + level.dimension().identifier()
                        + ", time=" + level.getGameTime()
                        + ", count=" + count);
    }

    public synchronized void releaseWorldTickFreeze(ServerLevel level) {
        if (level == null) {
            return;
        }
        Integer count = this.frozenWorldTicks.get(level);
        if (count == null || count <= 1) {
            this.frozenWorldTicks.remove(level);
            LumaLoadLog.event("tnt-replay", "freeze-release",
                    "dimension=" + level.dimension().identifier()
                            + ", time=" + level.getGameTime()
                            + ", count=0");
            return;
        }
        int next = count - 1;
        this.frozenWorldTicks.put(level, next);
        LumaLoadLog.event("tnt-replay", "freeze-release",
                "dimension=" + level.dimension().identifier()
                        + ", time=" + level.getGameTime()
                        + ", count=" + next);
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
        boolean suppressed = expiresAt >= now;
        if (suppressed && LumaDiagnosticsLog.fluidUndoEnabled()) {
            LumaDiagnosticsLog.fluidUndoEvent("replay-suppress",
                    "dimension=" + level.dimension().identifier()
                            + ", time=" + now
                            + ", pos=" + format(pos)
                            + ", expiresAt=" + expiresAt
                            + ", source=" + WorldMutationContext.currentSource()
                            + ", action=" + blank(WorldMutationContext.currentActionId())
                            + ", actor=" + blank(WorldMutationContext.currentActor()));
        }
        return suppressed;
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

    private static String samplePositions(Collection<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return "";
        }
        StringBuilder sample = new StringBuilder();
        int count = 0;
        for (BlockPos pos : positions) {
            if (pos == null) {
                continue;
            }
            if (sample.length() > 0) {
                sample.append("; ");
            }
            sample.append(format(pos));
            count += 1;
            if (count >= 12) {
                break;
            }
        }
        return sample.toString();
    }

    private static String format(BlockPos pos) {
        return pos == null ? "unknown" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }
}
