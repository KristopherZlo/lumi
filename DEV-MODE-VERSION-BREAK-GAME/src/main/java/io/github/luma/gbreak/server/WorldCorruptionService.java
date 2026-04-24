package io.github.luma.gbreak.server;

import io.github.luma.gbreak.block.GBreakBlocks;
import io.github.luma.gbreak.state.CorruptionSettings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import net.minecraft.util.math.random.LocalRandom;
import net.minecraft.world.World;

public final class WorldCorruptionService {

    private static final int UPDATE_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;
    private static final long WORLD_MASK_SEED = 0x4C554D41474C4954L;

    private final CorruptionSettings settings = CorruptionSettings.getInstance();
    private final Map<CorruptedBlockKey, RestorableBlock> originals = new LinkedHashMap<>();
    private final Deque<RestorableBlock> restoreQueue = new ArrayDeque<>();
    private final SkyCorruptionDisplayService skyDisplayService = new SkyCorruptionDisplayService();
    private final CorruptionParticleService particleService = new CorruptionParticleService();

    private UUID targetPlayerId;
    private boolean corrupting;
    private boolean restoring;
    private int cachedHorizontalRadius = -1;
    private int cachedVerticalRadius = -1;
    private List<BlockPos> cachedSearchOffsets = List.of();
    private final SimplexNoiseSampler noiseSampler = new SimplexNoiseSampler(new LocalRandom(WORLD_MASK_SEED));

    public StartResult start(ServerPlayerEntity player) {
        if (this.corrupting) {
            return new StartResult(false, this.originals.size());
        }
        if (this.restoring) {
            return new StartResult(false, this.restoreQueue.size());
        }

        this.targetPlayerId = player.getUuid();
        this.corrupting = true;
        return new StartResult(true, this.originals.size());
    }

    public StopResult stop() {
        boolean wasRunning = this.corrupting || this.restoring || !this.originals.isEmpty();
        this.corrupting = false;
        this.restoring = !this.originals.isEmpty();
        this.targetPlayerId = null;
        this.rebuildRestoreQueue();
        int removedDisplays = this.skyDisplayService.clear();
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
            this.restoreBatch(server, this.settings.restoreBatchSize());
            return;
        }
        if (!this.corrupting) {
            return;
        }

        ServerPlayerEntity player = this.findTargetPlayer(server);
        if (player == null || player.isSpectator()) {
            return;
        }
        this.syncCorruptionMask(server, player);
        this.skyDisplayService.spawnAround(player);
        this.particleService.tick(player);
    }

    void restoreAllImmediately(MinecraftServer server) {
        this.corrupting = false;
        this.restoring = true;
        this.targetPlayerId = null;
        this.skyDisplayService.clear();
        this.rebuildRestoreQueue();
        this.restoreBatch(server, Integer.MAX_VALUE);
    }

    private ServerPlayerEntity findTargetPlayer(MinecraftServer server) {
        if (this.targetPlayerId == null) {
            return null;
        }
        return server.getPlayerManager().getPlayer(this.targetPlayerId);
    }

    private void syncCorruptionMask(MinecraftServer server, ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        BlockPos origin = player.getBlockPos();
        int horizontalRadius = this.effectiveHorizontalRadius(server);
        List<NoiseCandidate> desiredCandidates = this.resolveDesiredCandidates(world, origin, horizontalRadius);
        Set<CorruptedBlockKey> desiredKeys = new HashSet<>((desiredCandidates.size() * 4 / 3) + 1);
        for (NoiseCandidate candidate : desiredCandidates) {
            desiredKeys.add(candidate.key());
        }

        this.restoreOutsideMask(server, desiredKeys, this.settings.restoreBatchSize());
        this.corruptDesiredBatch(world, desiredCandidates);
    }

    private List<NoiseCandidate> resolveDesiredCandidates(ServerWorld world, BlockPos origin, int horizontalRadius) {
        List<NoiseCandidate> candidates = new ArrayList<>();
        List<BlockPos> offsets = this.searchOffsets(horizontalRadius);
        for (BlockPos offset : offsets) {
            BlockPos candidatePos = origin.add(offset);
            if (!world.isInBuildLimit(candidatePos)) {
                continue;
            }

            CorruptedBlockKey key = new CorruptedBlockKey(world.getRegistryKey(), candidatePos.toImmutable());
            RestorableBlock tracked = this.originals.get(key);
            BlockState state = tracked == null ? world.getBlockState(candidatePos) : tracked.originalState();
            if (!this.canCorrupt(state)) {
                continue;
            }

            double noiseValue = this.noiseValue(candidatePos);
            if (this.isWorldMaskPosition(candidatePos, noiseValue, offsets.size())) {
                candidates.add(new NoiseCandidate(key, noiseValue));
            }
        }

        return candidates;
    }

    private int effectiveHorizontalRadius(MinecraftServer server) {
        int renderDistanceBlocks = Math.max(2, server.getPlayerManager().getViewDistance()) * 16;
        return Math.max(16, renderDistanceBlocks * this.settings.renderRadiusPercent() / 100);
    }

    private double noiseValue(BlockPos pos) {
        double noiseScale = this.settings.noiseScale();
        double detailNoiseScale = this.settings.detailNoiseScale();
        double base = this.noiseSampler.sample(pos.getX() * noiseScale, pos.getY() * noiseScale, pos.getZ() * noiseScale);
        double detail = this.noiseSampler.sample(
                1000.0D + pos.getX() * detailNoiseScale,
                -1000.0D + pos.getY() * detailNoiseScale,
                pos.getZ() * detailNoiseScale
        );
        return base + detail * 0.35D;
    }

    private boolean isWorldMaskPosition(BlockPos pos, double noiseValue, int sampledPositionCount) {
        double density = (double) this.settings.targetCorruptedBlocks() / Math.max(1, sampledPositionCount);
        double normalizedNoise = this.clamp((noiseValue + 1.35D) / 2.7D, 0.0D, 1.0D);
        double clusteredDensity = density * (0.05D + Math.pow(normalizedNoise, 3.0D) * 8.0D);
        return this.positionHashUnit(pos) < this.clamp(clusteredDensity, 0.0002D, 0.95D);
    }

    private double positionHashUnit(BlockPos pos) {
        long hash = pos.asLong() ^ WORLD_MASK_SEED;
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return (hash >>> 11) * 0x1.0p-53D;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void corruptDesiredBatch(ServerWorld world, List<NoiseCandidate> desiredCandidates) {
        int changed = 0;
        int applyBatchSize = this.settings.applyBatchSize();
        for (NoiseCandidate candidate : desiredCandidates) {
            if (changed >= applyBatchSize) {
                return;
            }
            if (this.originals.containsKey(candidate.key())) {
                continue;
            }

            BlockPos candidatePos = candidate.key().pos();
            BlockState currentState = world.getBlockState(candidatePos);
            if (!this.canCorrupt(currentState)) {
                continue;
            }

            this.originals.put(candidate.key(), new RestorableBlock(candidate.key(), currentState));
            world.setBlockState(candidatePos, GBreakBlocks.MISSING_TEXTURE.getDefaultState(), UPDATE_FLAGS);
            changed++;
        }
    }

    private void restoreOutsideMask(MinecraftServer server, Set<CorruptedBlockKey> desiredKeys, int limit) {
        int restored = 0;
        var iterator = this.originals.entrySet().iterator();
        while (iterator.hasNext() && restored < limit) {
            Map.Entry<CorruptedBlockKey, RestorableBlock> entry = iterator.next();
            if (desiredKeys.contains(entry.getKey())) {
                continue;
            }

            RestorableBlock block = entry.getValue();
            ServerWorld world = server.getWorld(block.key().worldKey());
            if (world != null && world.isInBuildLimit(block.key().pos())) {
                world.setBlockState(block.key().pos(), block.originalState(), UPDATE_FLAGS);
            }
            iterator.remove();
            restored++;
        }
    }

    private boolean canCorrupt(BlockState state) {
        return !state.isAir()
                && !state.hasBlockEntity()
                && !state.isOf(GBreakBlocks.MISSING_TEXTURE);
    }

    private void rebuildRestoreQueue() {
        this.restoreQueue.clear();
        this.restoreQueue.addAll(this.originals.values());
    }

    private void restoreBatch(MinecraftServer server, int limit) {
        int restored = 0;
        while (!this.restoreQueue.isEmpty() && restored < limit) {
            RestorableBlock block = this.restoreQueue.removeFirst();
            ServerWorld world = server.getWorld(block.key().worldKey());
            if (world != null && world.isInBuildLimit(block.key().pos())) {
                world.setBlockState(block.key().pos(), block.originalState(), UPDATE_FLAGS);
            }
            this.originals.remove(block.key());
            restored++;
        }

        if (this.restoreQueue.isEmpty()) {
            this.restoring = false;
        }
    }

    private List<BlockPos> searchOffsets(int horizontalRadius) {
        int verticalRadius = this.settings.verticalRadius();
        if (this.cachedHorizontalRadius == horizontalRadius && this.cachedVerticalRadius == verticalRadius) {
            return this.cachedSearchOffsets;
        }

        this.cachedHorizontalRadius = horizontalRadius;
        this.cachedVerticalRadius = verticalRadius;
        this.cachedSearchOffsets = this.buildSearchOffsets(horizontalRadius, verticalRadius);
        return this.cachedSearchOffsets;
    }

    private List<BlockPos> buildSearchOffsets(int horizontalRadius, int verticalRadius) {
        List<BlockPos> offsets = new ArrayList<>();
        int horizontalStep = Math.max(1, horizontalRadius / 32);
        for (int y = -verticalRadius; y <= verticalRadius; y++) {
            for (int x = -horizontalRadius; x <= horizontalRadius; x += horizontalStep) {
                for (int z = -horizontalRadius; z <= horizontalRadius; z += horizontalStep) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    offsets.add(new BlockPos(x, y, z));
                }
            }
        }
        return List.copyOf(offsets);
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
}
