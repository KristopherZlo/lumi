package io.github.luma.minecraft.world;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.debug.HistoryDebugLog;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Holds exact redstone-like replay states steady while vanilla delayed updates
 * from the previous operation finish ticking.
 */
public final class ExactReplayStateGuard {

    private static final int MAX_REASSERTIONS_PER_TICK = 4096;
    private static final ExactReplayStateGuard INSTANCE = new ExactReplayStateGuard();

    private final PersistentBlockStatePolicy blockStatePolicy = new PersistentBlockStatePolicy();
    private final ExactReplayGuardBlockPolicy guardBlockPolicy = new ExactReplayGuardBlockPolicy();
    private final BlockPlacementUpdateDecider updateDecider = new BlockPlacementUpdateDecider();
    private final WorldApplyBlockUpdatePolicy updatePolicy = new WorldApplyBlockUpdatePolicy();
    private final WorldReplayTickSuppression replaySuppression = WorldReplayTickSuppression.getInstance();
    private final HistoryDebugLog historyDebugLog = new HistoryDebugLog();
    private final Map<ServerLevel, GuardedWorld> guardedWorlds = new IdentityHashMap<>();

    public static ExactReplayStateGuard getInstance() {
        return INSTANCE;
    }

    synchronized void guard(ServerLevel level, Collection<PreparedBlockPlacement> placements, int ticks) {
        if (level == null || placements == null || placements.isEmpty() || ticks <= 0) {
            return;
        }

        Set<BlockPos> callbackProtectedPositions = new LinkedHashSet<>();
        GuardedWorld guardedWorld = this.guardedWorlds.computeIfAbsent(level, ignored -> new GuardedWorld());
        long expiresAt = level.getGameTime() + ticks;
        int exactStates = 0;
        for (PreparedBlockPlacement placement : placements) {
            PreparedBlockPlacement copied = this.copy(placement);
            if (copied == null) {
                continue;
            }
            if (this.shouldGuard(copied)) {
                guardedWorld.guard(copied, expiresAt);
                exactStates += 1;
            }
            callbackProtectedPositions.addAll(this.callbackSuppressionPositions(copied));
        }

        this.replaySuppression.protect(level, callbackProtectedPositions, ticks);
        this.historyDebugLog.logExactGuard(level, exactStates, callbackProtectedPositions.size(), ticks);
        guardedWorld.removeExpired(level.getGameTime());
        if (guardedWorld.isEmpty()) {
            this.guardedWorlds.remove(level);
        }
    }

    void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            this.tick(level);
        }
    }

    synchronized void clear(ServerLevel level) {
        if (level == null) {
            return;
        }
        this.guardedWorlds.remove(level);
        this.replaySuppression.clear(level);
    }

    public void releaseForExplicitMutation(ServerLevel level, WorldMutationSource source) {
        if (!this.isExplicitBuilderSource(source)) {
            return;
        }
        this.clear(level);
    }

    boolean shouldGuard(PreparedBlockPlacement placement) {
        return placement != null && this.shouldGuard(placement.state());
    }

    List<BlockPos> callbackSuppressionPositions(PreparedBlockPlacement placement) {
        if (placement == null || placement.pos() == null
                || !this.guardBlockPolicy.shouldSuppressCallbacks(placement.state())) {
            return List.of();
        }

        List<BlockPos> positions = new ArrayList<>();
        BlockPos pos = placement.pos();
        positions.add(pos.immutable());
        for (Direction direction : Direction.values()) {
            positions.add(pos.relative(direction).immutable());
        }
        return List.copyOf(positions);
    }

    boolean isExplicitBuilderSource(WorldMutationSource source) {
        if (source == null) {
            return false;
        }
        return switch (source) {
            case PLAYER, ENTITY, EXPLOSIVE, EXTERNAL_TOOL, WORLDEDIT, FAWE, AXIOM -> true;
            case EXPLOSION, FLUID, FIRE, GROWTH, BLOCK_UPDATE, PISTON, FALLING_BLOCK, MOB, RESTORE, SYSTEM -> false;
        };
    }

    private void tick(ServerLevel level) {
        List<PreparedBlockPlacement> placements = this.nextPlacements(level);
        if (placements.isEmpty()) {
            return;
        }

        try (
                WorldMutationContext.SourceFrame ignoredSource =
                        WorldMutationContext.pushSource(WorldMutationSource.RESTORE);
                WorldMutationContext.SuppressionFrame ignoredSuppression =
                        WorldMutationContext.pushCaptureSuppression()
        ) {
            for (PreparedBlockPlacement placement : placements) {
                this.applyExact(level, placement);
            }
        }
    }

    private synchronized List<PreparedBlockPlacement> nextPlacements(ServerLevel level) {
        if (level == null) {
            return List.of();
        }

        GuardedWorld guardedWorld = this.guardedWorlds.get(level);
        if (guardedWorld == null) {
            return List.of();
        }

        guardedWorld.removeExpired(level.getGameTime());
        if (guardedWorld.isEmpty()) {
            this.guardedWorlds.remove(level);
            return List.of();
        }
        return guardedWorld.nextPlacements(MAX_REASSERTIONS_PER_TICK);
    }

    private boolean shouldGuard(BlockState state) {
        return this.guardBlockPolicy.shouldGuard(state);
    }

    private boolean applyExact(ServerLevel level, PreparedBlockPlacement placement) {
        PersistentBlockStatePolicy.PersistentBlockState target = this.blockStatePolicy.normalize(
                placement.state(),
                placement.blockEntityTag()
        );
        BlockPos pos = placement.pos();
        BlockState currentState = level.getBlockState(pos);
        BlockState targetState = target.state();
        CompoundTag targetBlockEntityTag = target.blockEntityTag();
        if (!this.updateDecider.requiresUpdate(level, pos, currentState, targetState, targetBlockEntityTag)) {
            this.historyDebugLog.logExactReplay(null, level, "guard-tick", pos, currentState, targetState, false);
            return false;
        }

        level.removeBlockEntity(pos);
        level.setBlock(pos, targetState, this.updatePolicy.placementFlags(targetState));
        if (targetBlockEntityTag != null) {
            BlockEntity blockEntity = BlockEntity.loadStatic(
                    pos,
                    targetState,
                    targetBlockEntityTag.copy(),
                    level.registryAccess()
            );
            if (blockEntity != null) {
                level.setBlockEntity(blockEntity);
            }
        }
        this.historyDebugLog.logExactReplay(null, level, "guard-tick", pos, currentState, targetState, true);
        return true;
    }

    private PreparedBlockPlacement copy(PreparedBlockPlacement placement) {
        if (placement == null || placement.pos() == null || placement.state() == null) {
            return null;
        }
        return new PreparedBlockPlacement(
                placement.pos().immutable(),
                placement.state(),
                placement.blockEntityTag() == null ? null : placement.blockEntityTag().copy(),
                placement.replayHint()
        );
    }

    private static final class GuardedWorld {

        private final Map<Long, GuardedPlacement> placements = new LinkedHashMap<>();
        private int cursor;

        void guard(PreparedBlockPlacement placement, long expiresAt) {
            this.placements.put(placement.pos().asLong(), new GuardedPlacement(placement, expiresAt));
            if (this.cursor >= this.placements.size()) {
                this.cursor = 0;
            }
        }

        List<PreparedBlockPlacement> nextPlacements(int maxPlacements) {
            if (this.placements.isEmpty() || maxPlacements <= 0) {
                return List.of();
            }

            List<GuardedPlacement> values = new ArrayList<>(this.placements.values());
            int limit = Math.min(maxPlacements, values.size());
            List<PreparedBlockPlacement> selected = new ArrayList<>(limit);
            int start = Math.floorMod(this.cursor, values.size());
            for (int offset = 0; offset < limit; offset += 1) {
                selected.add(values.get((start + offset) % values.size()).placement());
            }
            this.cursor = (start + limit) % values.size();
            return selected;
        }

        void removeExpired(long now) {
            this.placements.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
            if (this.cursor >= this.placements.size()) {
                this.cursor = 0;
            }
        }

        boolean isEmpty() {
            return this.placements.isEmpty();
        }
    }

    private record GuardedPlacement(
            PreparedBlockPlacement placement,
            long expiresAt
    ) {
    }
}
