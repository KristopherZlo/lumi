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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Holds exact redstone-like replay states steady while vanilla delayed updates
 * from the previous operation finish ticking.
 */
public final class ExactReplayStateGuard {

    private static final int MAX_REASSERTIONS_PER_TICK = 4096;
    private static final int MAX_IMMEDIATE_FLUID_TAIL_REASSERTIONS = 512;
    private static final ExactReplayStateGuard INSTANCE = new ExactReplayStateGuard();

    private final ExactReplayGuardBlockPolicy guardBlockPolicy = new ExactReplayGuardBlockPolicy();
    private final WorldReplayTickSuppression replaySuppression = WorldReplayTickSuppression.getInstance();
    private final FluidReplayUpdateScheduler fluidReplayUpdateScheduler = new FluidReplayUpdateScheduler();
    private final HistoryDebugLog historyDebugLog = new HistoryDebugLog();
    private final SafeExactReplayStateApplier exactReplayStateApplier;
    private final Map<ServerLevel, GuardedWorld> guardedWorlds = new IdentityHashMap<>();

    ExactReplayStateGuard() {
        this(new SafeExactReplayStateApplier());
    }

    ExactReplayStateGuard(SafeExactReplayStateApplier exactReplayStateApplier) {
        this.exactReplayStateApplier = exactReplayStateApplier == null
                ? new SafeExactReplayStateApplier()
                : exactReplayStateApplier;
    }

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
        this.addFluidReplayTargets(placements, callbackProtectedPositions);
        exactStates += this.guardConnectedFluidTails(level, placements, guardedWorld, expiresAt, callbackProtectedPositions);

        this.replaySuppression.protect(level, callbackProtectedPositions, ticks);
        this.fluidReplayUpdateScheduler.schedule(level, placements);
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
        return placement != null
                && (placement.replayHint().suppressesPostReplayFluid()
                || placement.replayHint().suppressesPostReplayMechanism()
                || this.shouldGuard(placement.state()));
    }

    List<BlockPos> callbackSuppressionPositions(PreparedBlockPlacement placement) {
        if (placement == null || placement.pos() == null) {
            return List.of();
        }
        if (!placement.replayHint().suppressesPostReplayMechanism()
                && !this.guardBlockPolicy.shouldSuppressCallbacks(placement.state())) {
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
        GuardedWorld guardedWorld = this.guardedWorld(level);
        if (guardedWorld == null) {
            return;
        }
        List<PreparedBlockPlacement> placements = this.nextPlacements(level, guardedWorld);
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
                SafeExactReplayStateApplier.ApplyResult result = this.applyExact(level, placement);
                if (result.quarantined()) {
                    guardedWorld.remove(placement.pos());
                }
            }
        }
        this.removeEmptyGuardedWorld(level, guardedWorld);
    }

    private synchronized GuardedWorld guardedWorld(ServerLevel level) {
        if (level == null) {
            return null;
        }
        GuardedWorld guardedWorld = this.guardedWorlds.get(level);
        if (guardedWorld == null) {
            return null;
        }
        guardedWorld.removeExpired(level.getGameTime());
        if (guardedWorld.isEmpty()) {
            this.guardedWorlds.remove(level);
            return null;
        }
        return guardedWorld;
    }

    private List<PreparedBlockPlacement> nextPlacements(ServerLevel level, GuardedWorld guardedWorld) {
        if (level == null || guardedWorld == null) {
            return List.of();
        }
        return guardedWorld.nextPlacements(MAX_REASSERTIONS_PER_TICK);
    }

    private synchronized void removeEmptyGuardedWorld(ServerLevel level, GuardedWorld guardedWorld) {
        if (level != null && guardedWorld != null && guardedWorld.isEmpty()) {
            this.guardedWorlds.remove(level);
        }
    }

    private boolean shouldGuard(BlockState state) {
        return this.guardBlockPolicy.shouldGuard(state);
    }

    private int guardConnectedFluidTails(
            ServerLevel level,
            Collection<PreparedBlockPlacement> placements,
            GuardedWorld guardedWorld,
            long expiresAt,
            Set<BlockPos> callbackProtectedPositions
    ) {
        Set<BlockPos> tailPositions = this.fluidReplayUpdateScheduler.collectFluidTailCleanupPositions(
                placements,
                level::getFluidState,
                pos -> level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
        );
        if (tailPositions.isEmpty()) {
            return 0;
        }

        List<PreparedBlockPlacement> cleanupPlacements = new ArrayList<>(tailPositions.size());
        for (BlockPos pos : tailPositions) {
            PreparedBlockPlacement placement = new PreparedBlockPlacement(
                    pos.immutable(),
                    Blocks.AIR.defaultBlockState(),
                    null,
                    PreparedBlockPlacement.ReplayHint.SUPPRESS_POST_REPLAY_FLUID
            );
            guardedWorld.guard(placement, expiresAt);
            callbackProtectedPositions.add(placement.pos());
            cleanupPlacements.add(placement);
        }

        this.applyImmediateFluidTailCleanup(level, cleanupPlacements);
        return cleanupPlacements.size();
    }

    private void addFluidReplayTargets(
            Collection<PreparedBlockPlacement> placements,
            Set<BlockPos> callbackProtectedPositions
    ) {
        if (placements == null || callbackProtectedPositions == null) {
            return;
        }
        for (PreparedBlockPlacement placement : placements) {
            if (this.isFluidRemovalTarget(placement)) {
                callbackProtectedPositions.add(placement.pos().immutable());
            }
        }
    }

    private boolean isFluidRemovalTarget(PreparedBlockPlacement placement) {
        return placement != null
                && placement.pos() != null
                && placement.state() != null
                && placement.replayHint().suppressesPostReplayFluid()
                && placement.state().getFluidState().isEmpty();
    }

    private void applyImmediateFluidTailCleanup(ServerLevel level, List<PreparedBlockPlacement> placements) {
        int limit = Math.min(MAX_IMMEDIATE_FLUID_TAIL_REASSERTIONS, placements.size());
        try (
                WorldMutationContext.SourceFrame ignoredSource =
                        WorldMutationContext.pushSource(WorldMutationSource.RESTORE);
            WorldMutationContext.SuppressionFrame ignoredSuppression =
                    WorldMutationContext.pushCaptureSuppression()
        ) {
            for (int index = 0; index < limit; index += 1) {
                SafeExactReplayStateApplier.ApplyResult result =
                        this.applyExact(level, placements.get(index), "guard-fluid-tail");
                if (result.quarantined()) {
                    GuardedWorld guardedWorld = this.guardedWorld(level);
                    if (guardedWorld != null) {
                        guardedWorld.remove(placements.get(index).pos());
                        this.removeEmptyGuardedWorld(level, guardedWorld);
                    }
                }
            }
        }
    }

    private SafeExactReplayStateApplier.ApplyResult applyExact(ServerLevel level, PreparedBlockPlacement placement) {
        return this.applyExact(level, placement, "guard-tick");
    }

    private SafeExactReplayStateApplier.ApplyResult applyExact(
            ServerLevel level,
            PreparedBlockPlacement placement,
            String phase
    ) {
        return this.exactReplayStateApplier.apply(level, placement, null, phase);
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

        void remove(BlockPos pos) {
            if (pos == null) {
                return;
            }
            this.placements.remove(pos.asLong());
            if (this.cursor >= this.placements.size()) {
                this.cursor = 0;
            }
        }
    }

    private record GuardedPlacement(
            PreparedBlockPlacement placement,
            long expiresAt
    ) {
    }
}
