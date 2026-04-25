package io.github.luma.gbreak.server;

import io.github.luma.gbreak.block.GBreakBlocks;
import io.github.luma.gbreak.corruption.CorruptionMaskSampler;
import io.github.luma.gbreak.state.CorruptionSettings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

public final class WorldCorruptionService {

    private static final int UPDATE_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;
    private static final int HEALING_BLACKOUT_DELAY_TICKS = 20;

    private final CorruptionSettings settings = CorruptionSettings.getInstance();
    private final CorruptionMaskSampler maskSampler = new CorruptionMaskSampler();
    private final Map<CorruptedBlockKey, RestorableBlock> originals = new LinkedHashMap<>();
    private final GroundCorruptionBatchQueue<NoiseCandidate> corruptionQueue = new GroundCorruptionBatchQueue<>();
    private final ArrayDeque<RestorableBlock> restoreQueue = new ArrayDeque<>();
    private final CorruptionRestoreWavePlanner restoreWavePlanner = new CorruptionRestoreWavePlanner();
    private final SkyCorruptionDisplayService skyDisplayService = new SkyCorruptionDisplayService();
    private final RisingEntityService risingEntityService = new RisingEntityService();
    private final RisingGroundBlockService risingGroundBlockService = new RisingGroundBlockService();
    private final TimeJitterService timeJitterService = new TimeJitterService();
    private final CorruptionParticleService particleService = new CorruptionParticleService();
    private final CorruptionRestoreFadeNotifier restoreFadeNotifier = new CorruptionRestoreFadeNotifier();
    private final CorruptionHealingWaveNotifier healingWaveNotifier = new CorruptionHealingWaveNotifier();
    private final CorruptionRestoreCadence restoreCadence = new CorruptionRestoreCadence();
    private final CorruptionRestoreWaveProgress restoreWaveProgress = new CorruptionRestoreWaveProgress();

    private UUID targetPlayerId;
    private CorruptionOrigin corruptionOrigin;
    private boolean corrupting;
    private boolean restoring;
    private int restoreStartDelayTicks;
    private int cachedHorizontalRadius = -1;
    private List<BlockPos> cachedSurfaceOffsets = List.of();

    public StartResult start(ServerPlayerEntity player) {
        if (this.corrupting) {
            return new StartResult(false, this.originals.size());
        }
        if (this.restoring) {
            return new StartResult(false, this.restoreQueue.size());
        }

        this.targetPlayerId = player.getUuid();
        this.corruptionOrigin = new CorruptionOrigin(player.getEntityWorld().getRegistryKey(), player.getBlockPos().toImmutable());
        this.corrupting = true;
        this.corruptionQueue.reset();
        return new StartResult(true, this.originals.size());
    }

    public StopResult stop(ServerPlayerEntity player) {
        boolean wasRunning = this.corrupting || this.restoring || !this.originals.isEmpty();
        this.corrupting = false;
        this.restoring = !this.originals.isEmpty();
        this.targetPlayerId = null;
        this.corruptionOrigin = new CorruptionOrigin(player.getEntityWorld().getRegistryKey(), player.getBlockPos().toImmutable());
        this.corruptionQueue.reset();
        this.timeJitterService.reset();
        this.restoreCadence.reset();
        this.restoreWaveProgress.reset();
        this.restoreStartDelayTicks = this.settings.healingBlackoutMode() ? HEALING_BLACKOUT_DELAY_TICKS : 0;
        this.rebuildRestoreQueue();
        this.risingEntityService.clear();
        this.risingGroundBlockService.clear();
        int removedDisplays = this.skyDisplayService.clear();
        if (this.restoring) {
            this.sendHealingWave(player);
        }
        return new StopResult(wasRunning, this.restoreQueue.size(), removedDisplays);
    }

    public StatusSnapshot status() {
        return new StatusSnapshot(
                this.corrupting,
                this.restoring,
                this.originals.size(),
                this.restoreQueue.size(),
                this.skyDisplayService.activeCount()
        );
    }

    void tick(MinecraftServer server) {
        this.skyDisplayService.tickExisting();
        if (this.restoring) {
            this.restoreQueuedBatch(server);
            return;
        }
        if (!this.corrupting) {
            return;
        }

        ServerPlayerEntity player = this.findTargetPlayer(server);
        if (player == null || player.isSpectator()) {
            return;
        }
        if (!this.corruptionQueue.isPlanned()) {
            this.buildCorruptionQueue(server, player);
        }
        this.corruptQueuedBatch(server);
        this.skyDisplayService.spawnAround(player);
        this.risingEntityService.tick(player);
        this.risingGroundBlockService.tick(player);
        this.timeJitterService.tick(server);
        this.particleService.tick(player);
    }

    void restoreAllImmediately(MinecraftServer server) {
        this.corrupting = false;
        this.restoring = true;
        this.targetPlayerId = null;
        this.corruptionOrigin = null;
        this.corruptionQueue.reset();
        this.timeJitterService.reset();
        this.risingEntityService.clear();
        this.risingGroundBlockService.clear();
        this.skyDisplayService.clear();
        this.restoreCadence.reset();
        this.restoreWaveProgress.reset();
        this.restoreStartDelayTicks = 0;
        this.rebuildRestoreQueue();
        this.restoreBatch(server, Integer.MAX_VALUE);
    }

    private ServerPlayerEntity findTargetPlayer(MinecraftServer server) {
        if (this.targetPlayerId == null) {
            return null;
        }
        return server.getPlayerManager().getPlayer(this.targetPlayerId);
    }

    private void buildCorruptionQueue(MinecraftServer server, ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        BlockPos origin = player.getBlockPos();
        int horizontalRadius = this.effectiveHorizontalRadius(server);
        this.corruptionQueue.load(this.resolveDesiredCandidates(world, origin, horizontalRadius));
    }

    private List<NoiseCandidate> resolveDesiredCandidates(ServerWorld world, BlockPos origin, int horizontalRadius) {
        List<NoiseCandidate> candidates = new ArrayList<>();
        List<BlockPos> offsets = this.surfaceOffsets(horizontalRadius);
        for (BlockPos offset : offsets) {
            int x = origin.getX() + offset.getX();
            int z = origin.getZ() + offset.getZ();
            double noiseValue = this.maskSampler.noiseValue(x, z, this.settings);
            if (!this.maskSampler.isWorldMaskColumn(x, z, noiseValue, this.settings)) {
                continue;
            }

            BlockPos candidatePos = this.surfacePos(world, x, z);
            if (candidatePos == null) {
                continue;
            }

            CorruptedBlockKey key = new CorruptedBlockKey(world.getRegistryKey(), candidatePos.toImmutable());
            RestorableBlock tracked = this.originals.get(key);
            BlockState state = tracked == null ? world.getBlockState(candidatePos) : tracked.originalState();
            if (!this.canCorrupt(state)) {
                continue;
            }

            candidates.add(new NoiseCandidate(key, noiseValue));
        }

        return candidates;
    }

    private BlockPos surfacePos(ServerWorld world, int x, int z) {
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos pos = new BlockPos(x, y, z);
        return world.isInBuildLimit(pos) ? pos : null;
    }

    private int effectiveHorizontalRadius(MinecraftServer server) {
        return this.maskSampler.effectiveHorizontalRadius(server.getPlayerManager().getViewDistance(), this.settings);
    }

    private void corruptQueuedBatch(MinecraftServer server) {
        int changed = 0;
        int applyBatchSize = this.settings.applyBatchSize();
        while (this.corruptionQueue.hasPending() && changed < applyBatchSize) {
            NoiseCandidate candidate = this.corruptionQueue.removeFirst();
            if (this.originals.containsKey(candidate.key())) {
                continue;
            }

            ServerWorld world = server.getWorld(candidate.key().worldKey());
            if (world == null || !world.isInBuildLimit(candidate.key().pos())) {
                continue;
            }

            BlockPos candidatePos = candidate.key().pos();
            BlockState currentState = world.getBlockState(candidatePos);
            if (!this.canCorrupt(currentState)) {
                continue;
            }

            this.originals.put(candidate.key(), new RestorableBlock(candidate.key(), currentState));
            world.setBlockState(candidatePos, this.groundCorruptionState(candidate), UPDATE_FLAGS);
            changed++;
        }
    }

    private BlockState groundCorruptionState(NoiseCandidate candidate) {
        BlockPos pos = candidate.key().pos();
        int leakIndex = Math.floorMod(pos.getX() * 31 + pos.getY() * 17 + pos.getZ() * 13, 4);
        return GBreakBlocks.GROUND_CORRUPTION.stateForLeakIndex(leakIndex);
    }

    private boolean canCorrupt(BlockState state) {
        return !state.isAir()
                && !state.hasBlockEntity()
                && !state.isOf(GBreakBlocks.MISSING_TEXTURE)
                && !state.isOf(GBreakBlocks.GROUND_CORRUPTION);
    }

    private void rebuildRestoreQueue() {
        this.restoreQueue.clear();
        this.restoreQueue.addAll(this.restoreWavePlanner.orderFromCenter(
                this.originals.values(),
                block -> this.restoreDistanceSquared(block.key())
        ));
    }

    private void sendHealingWave(ServerPlayerEntity player) {
        this.healingWaveNotifier.notifyStarted(
                player.getEntityWorld(),
                player.getBlockPos(),
                this.maxRestoreRadiusBlocks(),
                this.settings.cleanupSpreadBlocksPerStep(),
                this.settings.cleanupIntervalTicks(),
                this.restoreStartDelayTicks,
                this.settings.healingBlackoutMode()
        );
    }

    private int maxRestoreRadiusBlocks() {
        long maxDistanceSquared = 0L;
        for (RestorableBlock block : this.restoreQueue) {
            maxDistanceSquared = Math.max(maxDistanceSquared, this.restoreDistanceSquared(block.key()));
        }
        return Math.min(384, (int) Math.ceil(Math.sqrt(maxDistanceSquared)) + this.settings.cleanupSpreadBlocksPerStep());
    }

    private void restoreQueuedBatch(MinecraftServer server) {
        if (this.restoreStartDelayTicks > 0) {
            this.restoreStartDelayTicks--;
            return;
        }

        if (this.restoreCadence.shouldRestoreNow(this.settings.cleanupIntervalTicks())) {
            long allowedDistanceSquared = this.restoreWaveProgress.advanceAndAllowedDistanceSquared(
                    this.settings.cleanupSpreadBlocksPerStep()
            );
            this.restoreBatch(server, this.settings.restoreBatchSize(), allowedDistanceSquared);
        }
    }

    private void restoreBatch(MinecraftServer server, int limit) {
        this.restoreBatch(server, limit, Long.MAX_VALUE);
    }

    private void restoreBatch(MinecraftServer server, int limit, long allowedDistanceSquared) {
        int restored = 0;
        Map<ServerWorld, List<BlockPos>> restoredPositions = new LinkedHashMap<>();
        while (!this.restoreQueue.isEmpty() && restored < limit) {
            RestorableBlock block = this.restoreQueue.peekFirst();
            if (this.restoreDistanceSquared(block.key()) > allowedDistanceSquared) {
                break;
            }

            block = this.restoreQueue.removeFirst();
            ServerWorld world = server.getWorld(block.key().worldKey());
            if (world != null && world.isInBuildLimit(block.key().pos())) {
                world.setBlockState(block.key().pos(), block.originalState(), UPDATE_FLAGS);
                if (limit != Integer.MAX_VALUE) {
                    restoredPositions
                            .computeIfAbsent(world, ignored -> new ArrayList<>())
                            .add(block.key().pos().toImmutable());
                }
            }
            this.originals.remove(block.key());
            restored++;
        }

        restoredPositions.forEach(this.restoreFadeNotifier::notifyRestoredBlocks);

        if (this.restoreQueue.isEmpty()) {
            this.restoring = false;
            this.corruptionOrigin = null;
            this.restoreStartDelayTicks = 0;
        }
    }

    private long restoreDistanceSquared(CorruptedBlockKey key) {
        if (this.corruptionOrigin == null || !this.corruptionOrigin.worldKey().equals(key.worldKey())) {
            return 0L;
        }
        return this.restoreWavePlanner.distanceSquared(this.corruptionOrigin.center(), key.pos());
    }

    private List<BlockPos> surfaceOffsets(int horizontalRadius) {
        if (this.cachedHorizontalRadius == horizontalRadius) {
            return this.cachedSurfaceOffsets;
        }

        this.cachedHorizontalRadius = horizontalRadius;
        this.cachedSurfaceOffsets = this.maskSampler.buildSurfaceOffsets(horizontalRadius);
        return this.cachedSurfaceOffsets;
    }

    public record StartResult(boolean started, int trackedBlocks) {}

    public record StopResult(boolean wasRunning, int restoreQueueSize, int removedDisplays) {}

    public record StatusSnapshot(
            boolean corrupting,
            boolean restoring,
            int trackedBlocks,
            int restoreQueueSize,
            int activeDisplays
    ) {}

    private record CorruptedBlockKey(RegistryKey<World> worldKey, BlockPos pos) {}

    private record RestorableBlock(CorruptedBlockKey key, BlockState originalState) {}

    private record NoiseCandidate(CorruptedBlockKey key, double value) {}

    private record CorruptionOrigin(RegistryKey<World> worldKey, BlockPos center) {}
}
