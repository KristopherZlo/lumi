package io.github.luma.gametest;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.time.Duration;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Owns the deterministic 100k-block world mutation and verification pattern.
 */
final class LumiBackupStressWorkload {

    private static final int TARGET_BLOCKS = 100_000;
    private static final int CHUNK_COLUMNS = 25;
    private static final int CHUNK_ROWS = 16;
    private static final int TARGET_CHUNKS = CHUNK_COLUMNS * CHUNK_ROWS;
    private static final int CELLS_PER_CHUNK = TARGET_BLOCKS / TARGET_CHUNKS;
    private static final int BATCH_BLOCKS = 4096;
    private static final int BASE_CHUNK_X = 32;
    private static final int BASE_CHUNK_Z = 32;
    private static final int FAST_SET_FLAGS =
            Block.UPDATE_CLIENTS
                    | Block.UPDATE_KNOWN_SHAPE
                    | Block.UPDATE_SUPPRESS_DROPS
                    | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS
                    | Block.UPDATE_SKIP_ON_PLACE;

    private final String actor;

    LumiBackupStressWorkload(String actor) {
        if (TARGET_BLOCKS % TARGET_CHUNKS != 0) {
            throw new IllegalStateException("Backup stress target blocks must divide evenly across chunks");
        }
        this.actor = actor == null || actor.isBlank() ? "Lumi backup stress" : actor;
    }

    int targetBlocks() {
        return TARGET_BLOCKS;
    }

    int targetChunks() {
        return TARGET_CHUNKS;
    }

    void placeAll(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            StressState state,
            String label,
            boolean captureAsBuilderAction
    ) throws Exception {
        long startedAt = System.nanoTime();
        String actionId = captureAsBuilderAction ? UUID.randomUUID().toString() : "";
        int cursor = 0;
        while (cursor < TARGET_BLOCKS) {
            int from = cursor;
            cursor = singleplayer.getServer().computeOnServer(server ->
                    this.placeBatch(server.overworld(), from, state, actionId));
            context.waitTick();
        }
        LumaMod.LOGGER.info(
                "Lumi backup stress placement complete: label={} blocks={} chunks={} captured={} durationMs={}",
                label,
                TARGET_BLOCKS,
                TARGET_CHUNKS,
                captureAsBuilderAction,
                elapsedMillis(startedAt)
        );
    }

    void verifyAll(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            StressState expected,
            String label
    ) throws Exception {
        long startedAt = System.nanoTime();
        int cursor = 0;
        int mismatches = 0;
        String firstMismatch = "";
        while (cursor < TARGET_BLOCKS) {
            int from = cursor;
            VerificationStep step = singleplayer.getServer().computeOnServer(server ->
                    this.verifyBatch(server.overworld(), from, expected));
            cursor = step.nextIndex();
            mismatches += step.mismatches();
            if (firstMismatch.isBlank()) {
                firstMismatch = step.firstMismatch();
            }
            context.waitTick();
        }
        if (mismatches > 0) {
            throw new AssertionError(label + " verification failed: mismatches=" + mismatches
                    + " firstMismatch=" + firstMismatch);
        }
        LumaMod.LOGGER.info(
                "Lumi backup stress verification complete: label={} blocks={} durationMs={}",
                label,
                TARGET_BLOCKS,
                elapsedMillis(startedAt)
        );
    }

    private int placeBatch(ServerLevel level, int startIndex, StressState state, String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return this.placeBatchWithoutSource(level, startIndex, state);
        }
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(
                WorldMutationSource.PLAYER,
                this.actor,
                actionId,
                true
        )) {
            return this.placeBatchWithoutSource(level, startIndex, state);
        }
    }

    private int placeBatchWithoutSource(ServerLevel level, int startIndex, StressState state) {
        int end = Math.min(TARGET_BLOCKS, startIndex + BATCH_BLOCKS);
        for (int index = startIndex; index < end; index++) {
            level.setBlock(this.targetPos(level, index), this.stateFor(state, index), this.flagsFor(state, index));
        }
        return end;
    }

    private VerificationStep verifyBatch(ServerLevel level, int startIndex, StressState expected) {
        int end = Math.min(TARGET_BLOCKS, startIndex + BATCH_BLOCKS);
        int mismatches = 0;
        String firstMismatch = "";
        for (int index = startIndex; index < end; index++) {
            BlockPos pos = this.targetPos(level, index);
            Block actual = level.getBlockState(pos).getBlock();
            Block expectedBlock = this.expectedBlock(expected, index);
            if (actual != expectedBlock) {
                mismatches += 1;
                if (firstMismatch.isBlank()) {
                    firstMismatch = pos.getX() + "," + pos.getY() + "," + pos.getZ()
                            + " expected=" + expectedBlock
                            + " actual=" + actual;
                }
            }
        }
        return new VerificationStep(end, mismatches, firstMismatch);
    }

    private BlockState stateFor(StressState state, int index) {
        if (state == StressState.BASELINE && (index % CELLS_PER_CHUNK) == 0) {
            return Blocks.BARREL.defaultBlockState();
        }
        return state == StressState.BASELINE
                ? Blocks.STONE.defaultBlockState()
                : Blocks.DIAMOND_BLOCK.defaultBlockState();
    }

    private Block expectedBlock(StressState state, int index) {
        return this.stateFor(state, index).getBlock();
    }

    private int flagsFor(StressState state, int index) {
        return state == StressState.BASELINE && (index % CELLS_PER_CHUNK) == 0 ? 3 : FAST_SET_FLAGS;
    }

    private BlockPos targetPos(ServerLevel level, int index) {
        int chunkIndex = index / CELLS_PER_CHUNK;
        int localIndex = index % CELLS_PER_CHUNK;
        int chunkX = BASE_CHUNK_X + (chunkIndex % CHUNK_COLUMNS);
        int chunkZ = BASE_CHUNK_Z + (chunkIndex / CHUNK_COLUMNS);
        return new BlockPos(
                (chunkX << 4) + (localIndex & 15),
                this.targetY(level),
                (chunkZ << 4) + (localIndex >> 4)
        );
    }

    private int targetY(ServerLevel level) {
        int minimum = level.getMinY() + 16;
        int maximum = level.getMaxY() - 16;
        int preferred = level.getSeaLevel() + 48;
        return Math.max(minimum, Math.min(maximum, preferred));
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    enum StressState {
        BASELINE,
        MODIFIED
    }

    private record VerificationStep(int nextIndex, int mismatches, String firstMismatch) {

        private VerificationStep {
            firstMismatch = firstMismatch == null ? "" : firstMismatch;
        }
    }
}
