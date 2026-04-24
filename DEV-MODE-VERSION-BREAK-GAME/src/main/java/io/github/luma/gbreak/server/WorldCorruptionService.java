package io.github.luma.gbreak.server;

import io.github.luma.gbreak.block.GBreakBlocks;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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

    private static final int HORIZONTAL_RADIUS = 12;
    private static final int VERTICAL_RADIUS = 5;
    private static final int TARGET_CORRUPTED_BLOCKS = 96;
    private static final int CORRUPTION_BATCH_SIZE = 24;
    private static final int RESTORE_BATCH_SIZE = 144;
    private static final double NOISE_SCALE = 0.115D;
    private static final double DETAIL_NOISE_SCALE = 0.31D;
    private static final int UPDATE_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;
    private static final List<BlockPos> SEARCH_OFFSETS = buildSearchOffsets();

    private final Map<CorruptedBlockKey, RestorableBlock> originals = new LinkedHashMap<>();
    private final Deque<RestorableBlock> restoreQueue = new ArrayDeque<>();
    private final SkyCorruptionDisplayService skyDisplayService = new SkyCorruptionDisplayService();
    private final CorruptionParticleService particleService = new CorruptionParticleService();

    private UUID targetPlayerId;
    private boolean corrupting;
    private boolean restoring;
    private SimplexNoiseSampler noiseSampler = new SimplexNoiseSampler(new LocalRandom(0x4C554D41474C4954L));

    public StartResult start(ServerPlayerEntity player) {
        if (this.corrupting) {
            return new StartResult(false, this.originals.size());
        }
        if (this.restoring) {
            return new StartResult(false, this.restoreQueue.size());
        }

        this.targetPlayerId = player.getUuid();
        this.corrupting = true;
        this.noiseSampler = new SimplexNoiseSampler(new LocalRandom(ThreadLocalRandom.current().nextLong()));
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
            this.restoreBatch(server, RESTORE_BATCH_SIZE);
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
        List<NoiseCandidate> desiredCandidates = this.resolveDesiredCandidates(world, origin);
        Set<CorruptedBlockKey> desiredKeys = new HashSet<>((desiredCandidates.size() * 4 / 3) + 1);
        for (NoiseCandidate candidate : desiredCandidates) {
            desiredKeys.add(candidate.key());
        }

        this.restoreOutsideMask(server, desiredKeys, RESTORE_BATCH_SIZE);
        this.corruptDesiredBatch(world, desiredCandidates);
    }

    private List<NoiseCandidate> resolveDesiredCandidates(ServerWorld world, BlockPos origin) {
        List<NoiseCandidate> candidates = new ArrayList<>();
        for (BlockPos offset : SEARCH_OFFSETS) {
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

            candidates.add(new NoiseCandidate(key, this.noiseValue(candidatePos)));
        }

        candidates.sort((left, right) -> Double.compare(right.value(), left.value()));
        if (candidates.size() <= TARGET_CORRUPTED_BLOCKS) {
            return candidates;
        }
        return List.copyOf(candidates.subList(0, TARGET_CORRUPTED_BLOCKS));
    }

    private double noiseValue(BlockPos pos) {
        double base = this.noiseSampler.sample(pos.getX() * NOISE_SCALE, pos.getY() * NOISE_SCALE, pos.getZ() * NOISE_SCALE);
        double detail = this.noiseSampler.sample(
                1000.0D + pos.getX() * DETAIL_NOISE_SCALE,
                -1000.0D + pos.getY() * DETAIL_NOISE_SCALE,
                pos.getZ() * DETAIL_NOISE_SCALE
        );
        return base + detail * 0.35D;
    }

    private void corruptDesiredBatch(ServerWorld world, List<NoiseCandidate> desiredCandidates) {
        int changed = 0;
        for (NoiseCandidate candidate : desiredCandidates) {
            if (this.originals.size() >= TARGET_CORRUPTED_BLOCKS || changed >= CORRUPTION_BATCH_SIZE) {
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

    private static List<BlockPos> buildSearchOffsets() {
        List<BlockPos> offsets = new ArrayList<>();
        for (int y = -VERTICAL_RADIUS; y <= VERTICAL_RADIUS; y++) {
            for (int x = -HORIZONTAL_RADIUS; x <= HORIZONTAL_RADIUS; x++) {
                for (int z = -HORIZONTAL_RADIUS; z <= HORIZONTAL_RADIUS; z++) {
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
