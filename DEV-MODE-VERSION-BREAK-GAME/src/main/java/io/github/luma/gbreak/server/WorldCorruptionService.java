package io.github.luma.gbreak.server;

import io.github.luma.gbreak.block.GBreakBlocks;
import io.github.luma.gbreak.corruption.CorruptionMaskSampler;
import io.github.luma.gbreak.state.CorruptionSettings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    private final CorruptionSettings settings = CorruptionSettings.getInstance();
    private final CorruptionMaskSampler maskSampler = new CorruptionMaskSampler();
    private final Map<CorruptedBlockKey, RestorableBlock> originals = new LinkedHashMap<>();
    private final Deque<NoiseCandidate> corruptionQueue = new ArrayDeque<>();
    private final Deque<RestorableBlock> restoreQueue = new ArrayDeque<>();
    private final SkyCorruptionDisplayService skyDisplayService = new SkyCorruptionDisplayService();
    private final CorruptionParticleService particleService = new CorruptionParticleService();

    private UUID targetPlayerId;
    private boolean corrupting;
    private boolean restoring;
    private boolean corruptionPlanBuilt;
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
        this.corrupting = true;
        this.corruptionPlanBuilt = false;
        this.corruptionQueue.clear();
        return new StartResult(true, this.originals.size());
    }

    public StopResult stop() {
        boolean wasRunning = this.corrupting || this.restoring || !this.originals.isEmpty();
        this.corrupting = false;
        this.restoring = !this.originals.isEmpty();
        this.corruptionPlanBuilt = false;
        this.targetPlayerId = null;
        this.corruptionQueue.clear();
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
        if (!this.corruptionPlanBuilt) {
            this.buildCorruptionQueue(server, player);
            this.corruptionPlanBuilt = true;
        }
        this.corruptQueuedBatch(server);
        this.skyDisplayService.spawnAround(player);
        this.particleService.tick(player);
    }

    void restoreAllImmediately(MinecraftServer server) {
        this.corrupting = false;
        this.restoring = true;
        this.corruptionPlanBuilt = false;
        this.targetPlayerId = null;
        this.corruptionQueue.clear();
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

    private void buildCorruptionQueue(MinecraftServer server, ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        BlockPos origin = player.getBlockPos();
        int horizontalRadius = this.effectiveHorizontalRadius(server);
        this.corruptionQueue.clear();
        this.corruptionQueue.addAll(this.resolveDesiredCandidates(world, origin, horizontalRadius));
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
        while (!this.corruptionQueue.isEmpty() && changed < applyBatchSize) {
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
            world.setBlockState(candidatePos, GBreakBlocks.MISSING_TEXTURE.getDefaultState(), UPDATE_FLAGS);
            changed++;
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
}
