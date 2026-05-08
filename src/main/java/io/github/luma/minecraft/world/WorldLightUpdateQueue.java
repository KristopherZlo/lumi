package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

final class WorldLightUpdateQueue {

    private static final int[][] NEIGHBOR_OFFSETS = {
            {1, 0, 0},
            {-1, 0, 0},
            {0, 1, 0},
            {0, -1, 0},
            {0, 0, 1},
            {0, 0, -1}
    };

    private LongSet exactPositions = new LongOpenHashSet();
    private LongSet surfaceCandidatePositions = new LongOpenHashSet();
    private final LongArrayList positions = new LongArrayList();
    private final LongArrayList dirtyChunks = new LongArrayList();
    private CompletableFuture<PreparedDrain> preparedDrainFuture;
    private int nextIndex = 0;
    private int dirtyChunkIndex = 0;
    private boolean drainPrepared = false;

    void add(SectionLightUpdateBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (BlockPos pos : batch.exactPositions()) {
            this.exactPositions.add(pos.asLong());
        }
        for (BlockPos pos : batch.surfaceCandidatePositions()) {
            this.surfaceCandidatePositions.add(pos.asLong());
        }
    }

    boolean hasPending() {
        if (this.preparedDrainFuture != null) {
            return true;
        }
        if (!this.drainPrepared) {
            return !this.exactPositions.isEmpty() || !this.surfaceCandidatePositions.isEmpty();
        }
        return this.nextIndex < this.positions.size() || this.dirtyChunkIndex < this.dirtyChunks.size();
    }

    int pendingCount() {
        if (this.preparedDrainFuture != null) {
            return this.exactPositions.size() + this.surfaceCandidatePositions.size();
        }
        if (this.drainPrepared) {
            return Math.max(0, this.positions.size() - this.nextIndex);
        }
        return this.exactPositions.size() + this.surfaceCandidatePositions.size();
    }

    int preparedCheckCount() {
        return this.positions.size();
    }

    int dirtyChunkCount() {
        return this.dirtyChunks.size();
    }

    boolean prepareDrainPositionsAsync(Executor executor) {
        if (this.drainPrepared) {
            return true;
        }
        if (this.preparedDrainFuture == null) {
            if (this.exactPositions.isEmpty() && this.surfaceCandidatePositions.isEmpty()) {
                return true;
            }
            LongSet exact = this.exactPositions;
            LongSet surface = this.surfaceCandidatePositions;
            this.exactPositions = new LongOpenHashSet();
            this.surfaceCandidatePositions = new LongOpenHashSet();
            this.preparedDrainFuture = CompletableFuture.supplyAsync(
                    () -> prepareDrain(exact, surface),
                    executor
            );
            return false;
        }
        if (!this.preparedDrainFuture.isDone()) {
            return false;
        }
        try {
            PreparedDrain prepared = this.preparedDrainFuture.join();
            this.positions.clear();
            this.positions.addAll(prepared.positions());
            this.dirtyChunks.clear();
            this.dirtyChunks.addAll(prepared.dirtyChunks());
            this.nextIndex = 0;
            this.dirtyChunkIndex = 0;
            this.drainPrepared = true;
            this.preparedDrainFuture = null;
            return true;
        } catch (CompletionException exception) {
            this.preparedDrainFuture = null;
            throw exception;
        }
    }

    int drain(ServerLevel level, int maxChecks, long deadlineNanos) {
        if (level == null || maxChecks <= 0 || !this.hasPending()) {
            return 0;
        }

        if (!this.drainPrepared) {
            return 0;
        }
        int applied = 0;
        while (this.nextIndex < this.positions.size() && applied < maxChecks && System.nanoTime() < deadlineNanos) {
            long packedPos = this.positions.getLong(this.nextIndex);
            level.getLightEngine().checkBlock(new BlockPos(
                    BlockPos.getX(packedPos),
                    BlockPos.getY(packedPos),
                    BlockPos.getZ(packedPos)
            ));
            this.nextIndex += 1;
            applied += 1;
        }
        this.clearIfComplete();
        return applied;
    }

    int markTouchedChunksUnsaved(ServerLevel level, int maxChunks, long deadlineNanos) {
        if (level == null || maxChunks <= 0 || !this.drainPrepared) {
            return 0;
        }
        int marked = 0;
        while (this.dirtyChunkIndex < this.dirtyChunks.size()
                && marked < maxChunks
                && System.nanoTime() < deadlineNanos) {
            long packedChunk = this.dirtyChunks.getLong(this.dirtyChunkIndex);
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX(packedChunk), chunkZ(packedChunk));
            if (chunk != null) {
                chunk.markUnsaved();
            }
            this.dirtyChunkIndex += 1;
            marked += 1;
        }
        this.clearIfComplete();
        return marked;
    }

    void prepareDrainPositions() {
        if (this.drainPrepared) {
            return;
        }

        PreparedDrain prepared = prepareDrain(this.exactPositions, this.surfaceCandidatePositions);
        this.positions.clear();
        this.positions.addAll(prepared.positions());
        this.dirtyChunks.clear();
        this.dirtyChunks.addAll(prepared.dirtyChunks());
        this.nextIndex = 0;
        this.dirtyChunkIndex = 0;
        this.drainPrepared = true;
    }

    private static PreparedDrain prepareDrain(LongSet exactPositions, LongSet surfaceCandidatePositions) {
        WorldLightUpdateQueue queue = new WorldLightUpdateQueue();
        queue.exactPositions = exactPositions == null ? new LongOpenHashSet() : exactPositions;
        queue.surfaceCandidatePositions = surfaceCandidatePositions == null
                ? new LongOpenHashSet()
                : surfaceCandidatePositions;
        LongSet selected = new LongOpenHashSet(queue.exactPositions);
        LongSet dirtyChunks = new LongOpenHashSet();
        for (long packedPos : queue.exactPositions) {
            dirtyChunks.add(chunkKey(packedPos));
        }
        for (long packedPos : queue.surfaceCandidatePositions) {
            dirtyChunks.add(chunkKey(packedPos));
            if (queue.isSurfaceCandidate(packedPos)) {
                selected.add(packedPos);
            }
        }
        LongArrayList positions = new LongArrayList(selected);
        positions.sort(LOCALITY_ORDER);
        LongArrayList chunks = new LongArrayList(dirtyChunks);
        chunks.sort(Long::compare);
        return new PreparedDrain(positions, chunks);
    }

    private static final LongComparator LOCALITY_ORDER = (first, second) -> {
        int sectionComparison = Long.compare(sectionKey(first), sectionKey(second));
        return sectionComparison != 0 ? sectionComparison : Long.compare(first, second);
    };

    private static long sectionKey(long packedPos) {
        return SectionPos.asLong(
                Math.floorDiv(BlockPos.getX(packedPos), 16),
                Math.floorDiv(BlockPos.getY(packedPos), 16),
                Math.floorDiv(BlockPos.getZ(packedPos), 16)
        );
    }

    private static long chunkKey(long packedPos) {
        return (((long) Math.floorDiv(BlockPos.getX(packedPos), 16)) << 32)
                ^ (Math.floorDiv(BlockPos.getZ(packedPos), 16) & 0xffffffffL);
    }

    private static int chunkX(long packedChunk) {
        return (int) (packedChunk >> 32);
    }

    private static int chunkZ(long packedChunk) {
        return (int) packedChunk;
    }

    private boolean isSurfaceCandidate(long packedPos) {
        int x = BlockPos.getX(packedPos);
        int y = BlockPos.getY(packedPos);
        int z = BlockPos.getZ(packedPos);
        for (int[] offset : NEIGHBOR_OFFSETS) {
            if (!this.surfaceCandidatePositions.contains(BlockPos.asLong(
                    x + offset[0],
                    y + offset[1],
                    z + offset[2]
            ))) {
                return true;
            }
        }
        return false;
    }

    private void clearIfComplete() {
        if (!this.hasPending()) {
            this.exactPositions.clear();
            this.surfaceCandidatePositions.clear();
            this.positions.clear();
            this.dirtyChunks.clear();
            this.nextIndex = 0;
            this.dirtyChunkIndex = 0;
            this.drainPrepared = false;
        }
    }

    private record PreparedDrain(LongArrayList positions, LongArrayList dirtyChunks) {
    }
}
