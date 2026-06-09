package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyCostModelTest {

    @Test
    void recordsCostsByIndependentWorkKind() {
        ApplyCostModel model = new ApplyCostModel();

        model.record(ApplyWorkKind.SPARSE_DIRECT, 100, 10_000_000L);
        model.record(ApplyWorkKind.SECTION_REWRITE, 1, 30_000_000L);

        assertTrue(model.estimateNanos(ApplyWorkKind.SPARSE_DIRECT, 100) < model.estimateNanos(ApplyWorkKind.SECTION_REWRITE, 1));
        assertEquals(0L, model.estimateNanos(ApplyWorkKind.BLOCK_ENTITY, 4));
    }

    @Test
    void estimatesChunkFromObservedShapeSpecificCosts() {
        ApplyCostModel model = new ApplyCostModel();
        model.record(ApplyWorkKind.SPARSE_DIRECT, 100, 10_000_000L);
        model.record(ApplyWorkKind.BLOCK_ENTITY, 2, 4_000_000L);

        long estimated = model.estimateChunkNanos(this.sparseChunk(100, 2));

        assertTrue(estimated >= 14_000_000L);
    }

    @Test
    void summarizesChunkShapesAndTickCounters() {
        WorldApplyTickDiagnostics diagnostics = new WorldApplyTickDiagnostics();
        ChunkBatch batch = new ChunkBatch(
                new ChunkPoint(2, 3),
                Map.of(
                        0, nativeSection(0, SectionApplySafetyProfile.nativeSection("test")),
                        1, nativeSection(1, SectionApplySafetyProfile.sectionRewrite("test"))
                ),
                Map.of(2, sparseSection()),
                Map.of(new BlockPos(32, 32, 48), new CompoundTag()),
                EntityBatch.empty(),
                BatchState.COMPLETE
        );

        WorldApplyMetrics metrics = new WorldApplyMetrics();
        metrics.record(BlockCommitResult.direct(2, 2, 0, 1, 3, 1));
        WorldApplyTickDiagnostics.TickCounters counters = diagnostics.startTick(metrics);
        metrics.record(BlockCommitResult.direct(5, 4, 1, 2, 6, 2));
        metrics.record(BlockCommitResult.rewriteSection(4_096, 100, 3_996, 1, 8));
        metrics.recordLightChecks(2);
        counters.recordWork(7, 1, 5, 2, 3);
        counters.recordChunkStarted();
        counters.recordChunkFinished();

        assertEquals(3, diagnostics.chunkShape(batch).setTargets());
        assertEquals(3, diagnostics.chunkShape(batch).deleteTargets());
        assertEquals(
                "processed=5, changed=3, skipped=2, rewriteSections=0, nativeSections=0, directSections=1, fallbackSections=1, packets=1, blockEntityPackets=0, lightChecks=4, reason=CHUNK_NOT_LOADED",
                diagnostics.commitSummary(BlockCommitResult.combine(
                        BlockCommitResult.direct(3, 2, 1, 1, 4, 1),
                        BlockCommitResult.nativeFallback(2, 1, 1, BlockCommitFallbackReason.CHUNK_NOT_LOADED)
                ))
        );
        assertEquals(
                "stop=time-budget, workThisTick=7, nativeSectionsThisTick=1, nativeCellsThisTick=5, rewriteSectionsThisTick=2, directSectionsThisTick=3, chunksStarted=1, chunksFinished=1, processedDelta=4101, rewriteSectionsDelta=1, nativeSectionsDelta=0, fallbackSectionsDelta=0, lightChecksDelta=16, currentBatch=2:3, dispatcherPending=true, lightPending=4, redstonePending=5",
                counters.tickDetail("time-budget", metrics, "2:3", true, 4, 5)
        );
    }

    private ChunkBatch sparseChunk(int placements, int blockEntities) {
        List<PreparedBlockPlacement> preparedPlacements = new ArrayList<>();
        BitSet changedCells = new BitSet(4096);
        for (int index = 0; index < placements; index++) {
            BlockPos pos = new BlockPos(index & 15, index >> 8, (index >> 4) & 15);
            preparedPlacements.add(new PreparedBlockPlacement(pos, null, null));
            changedCells.set(((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15));
        }
        Map<BlockPos, net.minecraft.nbt.CompoundTag> blockEntityMap = blockEntities <= 0
                ? Map.of()
                : Map.of(new BlockPos(0, 0, 0), new net.minecraft.nbt.CompoundTag(), new BlockPos(1, 0, 0), new net.minecraft.nbt.CompoundTag());
        return new ChunkBatch(
                new ChunkPoint(0, 0),
                Map.of(0, new SectionBatch(0, changedCells, preparedPlacements)),
                blockEntityMap,
                EntityBatch.empty(),
                BatchState.COMPLETE
        );
    }

    private PreparedSectionApplyBatch nativeSection(int sectionY, SectionApplySafetyProfile safetyProfile) {
        LumiSectionBuffer buffer = LumiSectionBuffer.builder(sectionY)
                .set(0, Blocks.STONE.defaultBlockState(), null)
                .set(1, Blocks.AIR.defaultBlockState(), null)
                .build();
        return new PreparedSectionApplyBatch(new ChunkPoint(2, 3), sectionY, buffer, safetyProfile, false);
    }

    private SectionBatch sparseSection() {
        BitSet changedCells = new BitSet(4096);
        changedCells.set(0);
        changedCells.set(1);
        return new SectionBatch(
                2,
                changedCells,
                List.of(
                        new PreparedBlockPlacement(new BlockPos(32, 32, 48), Blocks.STONE.defaultBlockState(), null),
                        new PreparedBlockPlacement(new BlockPos(33, 32, 48), Blocks.AIR.defaultBlockState(), null)
                )
        );
    }
}
