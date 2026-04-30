package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.EntityBatch;
import io.github.luma.minecraft.world.LumiSectionBuffer;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.PreparedSectionApplyBatch;
import io.github.luma.minecraft.world.SectionApplySafetyClassifier;
import io.github.luma.minecraft.world.SectionChangeMask;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Runs large prepared-apply diagnostics from the singleplayer test command.
 */
final class SingleplayerBulkApplyDiagnostics {

    private static final int DENSE_CHUNK_WIDTH = 4;
    private static final int DENSE_SECTION_HEIGHT = 4;
    private static final int SPARSE_CHUNK_WIDTH = 32;
    private static final int SPARSE_SECTION_HEIGHT = 4;
    private static final int SPARSE_CELLS_PER_SECTION = 61;
    private static final int CHUNKS_PER_TICK = 16;
    private static final int CHUNK_PROGRESS_STEP = 128;

    private final ServerLevel level;
    private final String projectId;
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();
    private final SectionApplySafetyClassifier safetyClassifier = new SectionApplySafetyClassifier();
    private final List<Scenario> scenarios;
    private final Map<String, OperationMetric> operationMetrics = new LinkedHashMap<>();
    private final Set<Scenario> touchedScenarios = new LinkedHashSet<>();
    private final List<DiagnosticCheck> checks = new ArrayList<>();

    private ScenarioRun currentRun;
    private int scenarioIndex;
    private boolean finished;

    SingleplayerBulkApplyDiagnostics(ServerLevel level, BlockPos near, String projectId) {
        this.level = level;
        this.projectId = projectId == null || projectId.isBlank() ? "singleplayer-bulk-diagnostics" : projectId;
        this.scenarios = this.createScenarios(level, near);
    }

    StepResult advance(MinecraftServer server) {
        if (this.finished) {
            return StepResult.idle();
        }

        List<String> messages = new ArrayList<>();
        while (this.currentRun == null) {
            if (this.scenarioIndex >= this.scenarios.size()) {
                this.finished = true;
                messages.addAll(this.summaryLines());
                return new StepResult(null, messages, true);
            }

            Scenario scenario = this.scenarios.get(this.scenarioIndex);
            this.currentRun = new ScenarioRun(scenario);
            messages.add("Bulk diagnostics starting " + scenario.name() + ": "
                    + scenario.targetCellCount() + " target cells across "
                    + scenario.chunkCount() + " chunks");
        }

        RunStep step = this.currentRun.advance(server);
        messages.addAll(step.messages());
        if (step.operationHandle() != null) {
            return new StepResult(step.operationHandle(), messages, false);
        }
        if (step.finished()) {
            this.scenarioIndex += 1;
            this.currentRun = null;
            if (step.skipped()) {
                messages.add("Bulk diagnostics skipped " + step.scenarioName() + ": " + step.detail());
            } else {
                messages.add("Bulk diagnostics completed " + step.scenarioName() + ": " + step.detail());
            }
        }
        return new StepResult(null, messages, false);
    }

    void recordMetrics(OperationHandle handle, OperationSnapshot snapshot, String metrics) {
        if (handle == null || snapshot == null || metrics == null || metrics.isBlank()) {
            return;
        }
        this.operationMetrics.put(
                handle.label(),
                new OperationMetric(
                        handle.label(),
                        snapshot.progress().totalUnits(),
                        Duration.between(handle.startedAt(), snapshot.updatedAt()).toMillis(),
                        metrics
                )
        );
    }

    List<DiagnosticCheck> checks() {
        return List.copyOf(this.checks);
    }

    void cleanup() {
        if (this.touchedScenarios.isEmpty()) {
            return;
        }
        WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
            for (Scenario scenario : this.touchedScenarios) {
                scenario.forEachTargetCell(this.level, pos -> {
                    if (!this.level.getBlockState(pos).isAir()) {
                        this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                });
            }
        });
    }

    private List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Bulk apply diagnostics summary:");
        for (Scenario scenario : this.scenarios) {
            OperationMetric fill = this.operationMetrics.get(scenario.fillLabel());
            OperationMetric delete = this.operationMetrics.get(scenario.deleteLabel());
            String status = fill == null || delete == null ? "skipped" : "completed";
            lines.add("Bulk scenario: name=" + scenario.name()
                    + ", status=" + status
                    + ", targetCells=" + scenario.targetCellCount()
                    + ", fill=" + this.metricSummary(fill)
                    + ", delete=" + this.metricSummary(delete));
        }
        return lines;
    }

    private String metricSummary(OperationMetric metric) {
        if (metric == null) {
            return "none";
        }
        return "durationMs=" + metric.durationMillis()
                + ", units=" + metric.totalUnits()
                + ", metrics=[" + metric.metrics() + "]";
    }

    private List<Scenario> createScenarios(ServerLevel level, BlockPos near) {
        int baseChunkX = Math.floorDiv(near.getX(), 16);
        int baseChunkZ = Math.floorDiv(near.getZ(), 16);
        int highSectionY = Math.floorDiv(level.getMaxY() - 64, 16);
        int denseChunkX = baseChunkX;
        int denseChunkZ = baseChunkZ;
        int sparseChunkX = baseChunkX - (SPARSE_CHUNK_WIDTH / 2);
        int sparseChunkZ = baseChunkZ - (SPARSE_CHUNK_WIDTH / 2);

        return List.of(
                new Scenario(
                        "dense-rewrite-250k",
                        "bulk-diagnostic-dense-rewrite-fill",
                        "bulk-diagnostic-dense-rewrite-delete",
                        denseChunkX,
                        denseChunkZ,
                        highSectionY,
                        DENSE_CHUNK_WIDTH,
                        DENSE_CHUNK_WIDTH,
                        DENSE_SECTION_HEIGHT,
                        SectionChangeMask.ENTRY_COUNT,
                        false
                ),
                new Scenario(
                        "block-entity-fallback-250k",
                        "bulk-diagnostic-block-entity-fallback-fill",
                        "bulk-diagnostic-block-entity-fallback-delete",
                        denseChunkX,
                        denseChunkZ,
                        highSectionY,
                        DENSE_CHUNK_WIDTH,
                        DENSE_CHUNK_WIDTH,
                        DENSE_SECTION_HEIGHT,
                        SectionChangeMask.ENTRY_COUNT,
                        true
                ),
                new Scenario(
                        "sparse-direct-250k",
                        "bulk-diagnostic-sparse-direct-fill",
                        "bulk-diagnostic-sparse-direct-delete",
                        sparseChunkX,
                        sparseChunkZ,
                        highSectionY,
                        SPARSE_CHUNK_WIDTH,
                        SPARSE_CHUNK_WIDTH,
                        SPARSE_SECTION_HEIGHT,
                        SPARSE_CELLS_PER_SECTION,
                        false
                )
        );
    }

    private final class ScenarioRun {

        private final Scenario scenario;
        private Stage stage = Stage.PREFLIGHT;
        private int nextChunkIndex;
        private int nextProgressChunkLog = CHUNK_PROGRESS_STEP;
        private BlockPos firstOccupied;
        private int nonAirAfterDelete;

        private ScenarioRun(Scenario scenario) {
            this.scenario = scenario;
        }

        private RunStep advance(MinecraftServer server) {
            return switch (this.stage) {
                case PREFLIGHT -> this.preflight();
                case START_FILL -> this.startFill();
                case START_DELETE -> this.startDelete();
                case VERIFY_DELETE -> this.verifyDelete();
                case FINISHED -> RunStep.finished(this.scenario.name(), "already finished");
            };
        }

        private RunStep preflight() {
            List<String> messages = new ArrayList<>();
            int processed = this.processChunks(chunk -> {
                SingleplayerBulkApplyDiagnostics.this.level.getChunk(chunk.x(), chunk.z());
                BlockPos occupied = this.scenario.firstNonAirTarget(
                        SingleplayerBulkApplyDiagnostics.this.level,
                        chunk
                );
                if (this.firstOccupied == null && occupied != null) {
                    this.firstOccupied = occupied;
                }
            });
            this.appendChunkProgress(messages, "preflight");
            if (this.firstOccupied != null) {
                this.stage = Stage.FINISHED;
                return RunStep.skipped(
                        this.scenario.name(),
                        "target cell is not air at "
                                + SingleplayerBulkApplyDiagnostics.this.format(this.firstOccupied)
                );
            }
            if (processed <= 0 && this.nextChunkIndex >= this.scenario.chunkCount()) {
                this.stage = Stage.START_FILL;
                messages.add("Bulk diagnostics preflight complete for " + this.scenario.name());
            }
            return RunStep.messages(messages);
        }

        private RunStep startFill() {
            SingleplayerBulkApplyDiagnostics.this.touchedScenarios.add(this.scenario);
            this.stage = Stage.START_DELETE;
            OperationHandle handle = this.startOperation(
                    this.scenario.fillLabel(),
                    Blocks.STONE.defaultBlockState(),
                    true
            );
            return RunStep.operation(
                    handle,
                    "Queued bulk fill for " + this.scenario.name()
                            + " with " + this.scenario.targetCellCount() + " cells"
            );
        }

        private RunStep startDelete() {
            this.stage = Stage.VERIFY_DELETE;
            this.nextChunkIndex = 0;
            this.nextProgressChunkLog = CHUNK_PROGRESS_STEP;
            OperationHandle handle = this.startOperation(
                    this.scenario.deleteLabel(),
                    Blocks.AIR.defaultBlockState(),
                    false
            );
            return RunStep.operation(
                    handle,
                    "Queued bulk delete for " + this.scenario.name()
                            + " with " + this.scenario.targetCellCount() + " cells"
            );
        }

        private RunStep verifyDelete() {
            List<String> messages = new ArrayList<>();
            int processed = this.processChunks(chunk ->
                    this.nonAirAfterDelete += this.scenario.nonAirTargetCount(
                            SingleplayerBulkApplyDiagnostics.this.level,
                            chunk
                    ));
            this.appendChunkProgress(messages, "verify");
            if (processed <= 0 && this.nextChunkIndex >= this.scenario.chunkCount()) {
                this.stage = Stage.FINISHED;
                boolean passed = this.nonAirAfterDelete == 0;
                if (passed) {
                    SingleplayerBulkApplyDiagnostics.this.touchedScenarios.remove(this.scenario);
                }
                SingleplayerBulkApplyDiagnostics.this.checks.add(new DiagnosticCheck(
                        "Bulk " + this.scenario.name() + " delete left target cells as air",
                        passed,
                        "nonAirAfterDelete=" + this.nonAirAfterDelete
                ));
                SingleplayerBulkApplyDiagnostics.this.checks.add(new DiagnosticCheck(
                        "Bulk " + this.scenario.name() + " fill metrics were recorded",
                        SingleplayerBulkApplyDiagnostics.this.operationMetrics.containsKey(this.scenario.fillLabel()),
                        "label=" + this.scenario.fillLabel()
                ));
                SingleplayerBulkApplyDiagnostics.this.checks.add(new DiagnosticCheck(
                        "Bulk " + this.scenario.name() + " delete metrics were recorded",
                        SingleplayerBulkApplyDiagnostics.this.operationMetrics.containsKey(this.scenario.deleteLabel()),
                        "label=" + this.scenario.deleteLabel()
                ));
                return RunStep.finished(
                        this.scenario.name(),
                        "nonAirAfterDelete=" + this.nonAirAfterDelete
                );
            }
            return RunStep.messages(messages);
        }

        private OperationHandle startOperation(String label, BlockState targetState, boolean fill) {
            return SingleplayerBulkApplyDiagnostics.this.worldOperationManager.startPreparedApplyOperation(
                    SingleplayerBulkApplyDiagnostics.this.level,
                    SingleplayerBulkApplyDiagnostics.this.projectId,
                    label,
                    "blocks",
                    true,
                    progressSink -> {
                        progressSink.update(
                                OperationStage.PREPARING,
                                0,
                                this.scenario.targetCellCount(),
                                "Preparing " + this.scenario.name()
                        );
                        List<PreparedChunkBatch> batches = this.scenario.prepareBatches(
                                targetState,
                                fill,
                                SingleplayerBulkApplyDiagnostics.this.safetyClassifier
                        );
                        progressSink.update(
                                OperationStage.PREPARING,
                                this.scenario.targetCellCount(),
                                this.scenario.targetCellCount(),
                                "Prepared " + this.scenario.name()
                        );
                        return new WorldOperationManager.PreparedApplyOperation(batches, () -> {
                        });
                    }
            );
        }

        private int processChunks(ChunkConsumer consumer) {
            if (this.nextChunkIndex >= this.scenario.chunkCount()) {
                return 0;
            }
            int processed = 0;
            while (processed < CHUNKS_PER_TICK && this.nextChunkIndex < this.scenario.chunkCount()) {
                consumer.accept(this.scenario.chunkAt(this.nextChunkIndex));
                this.nextChunkIndex += 1;
                processed += 1;
            }
            return processed;
        }

        private void appendChunkProgress(List<String> messages, String action) {
            if (this.nextChunkIndex >= this.scenario.chunkCount()) {
                return;
            }
            if (this.nextChunkIndex < this.nextProgressChunkLog) {
                return;
            }
            messages.add("Bulk diagnostics " + action + " " + this.scenario.name()
                    + ": chunks=" + this.nextChunkIndex + "/" + this.scenario.chunkCount());
            this.nextProgressChunkLog += CHUNK_PROGRESS_STEP;
        }
    }

    private record Scenario(
            String name,
            String fillLabel,
            String deleteLabel,
            int startChunkX,
            int startChunkZ,
            int startSectionY,
            int chunkWidth,
            int chunkDepth,
            int sectionHeight,
            int cellsPerSection,
            boolean blockEntityFallback
    ) {

        private int chunkCount() {
            return this.chunkWidth * this.chunkDepth;
        }

        private int sectionCount() {
            return this.chunkCount() * this.sectionHeight;
        }

        private int targetCellCount() {
            return this.sectionCount() * this.cellsPerSection;
        }

        private ChunkPoint chunkAt(int index) {
            int localX = index % this.chunkWidth;
            int localZ = index / this.chunkWidth;
            return new ChunkPoint(this.startChunkX + localX, this.startChunkZ + localZ);
        }

        private List<PreparedChunkBatch> prepareBatches(
                BlockState targetState,
                boolean fill,
                SectionApplySafetyClassifier safetyClassifier
        ) {
            List<PreparedChunkBatch> batches = new ArrayList<>(this.chunkCount());
            for (int chunkIndex = 0; chunkIndex < this.chunkCount(); chunkIndex++) {
                ChunkPoint chunk = this.chunkAt(chunkIndex);
                List<PreparedSectionApplyBatch> nativeSections = new ArrayList<>();
                List<PreparedBlockPlacement> sparsePlacements = new ArrayList<>();
                for (int sectionOffset = 0; sectionOffset < this.sectionHeight; sectionOffset++) {
                    int sectionY = this.startSectionY + sectionOffset;
                    if (this.cellsPerSection == SectionChangeMask.ENTRY_COUNT) {
                        LumiSectionBuffer buffer = this.fullSectionBuffer(sectionY, targetState, fill);
                        nativeSections.add(new PreparedSectionApplyBatch(
                                chunk,
                                sectionY,
                                buffer,
                                safetyClassifier.classify(buffer, true),
                                true
                        ));
                    } else {
                        this.addSparsePlacements(chunk, sectionY, targetState, sparsePlacements);
                    }
                }
                batches.add(new PreparedChunkBatch(chunk, sparsePlacements, nativeSections, EntityBatch.empty()));
            }
            return List.copyOf(batches);
        }

        private LumiSectionBuffer fullSectionBuffer(int sectionY, BlockState targetState, boolean fill) {
            LumiSectionBuffer.Builder builder = LumiSectionBuffer.builder(sectionY);
            for (int localIndex = 0; localIndex < SectionChangeMask.ENTRY_COUNT; localIndex++) {
                BlockState state = targetState;
                if (fill && this.blockEntityFallback && localIndex == 0) {
                    state = Blocks.BARREL.defaultBlockState();
                }
                builder.set(localIndex, state, null);
            }
            return builder.build();
        }

        private void addSparsePlacements(
                ChunkPoint chunk,
                int sectionY,
                BlockState targetState,
                List<PreparedBlockPlacement> placements
        ) {
            for (int ordinal = 0; ordinal < this.cellsPerSection; ordinal++) {
                int localIndex = sparseLocalIndex(ordinal);
                placements.add(new PreparedBlockPlacement(
                        this.worldPos(chunk, sectionY, localIndex),
                        targetState,
                        null
                ));
            }
        }

        private BlockPos firstNonAirTarget(ServerLevel level, ChunkPoint chunk) {
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            for (int sectionOffset = 0; sectionOffset < this.sectionHeight; sectionOffset++) {
                int sectionY = this.startSectionY + sectionOffset;
                for (int ordinal = 0; ordinal < this.cellsPerSection; ordinal++) {
                    int localIndex = this.localIndexAt(ordinal);
                    this.setWorldPos(mutable, chunk, sectionY, localIndex);
                    if (!level.getBlockState(mutable).isAir()) {
                        return mutable.immutable();
                    }
                }
            }
            return null;
        }

        private int nonAirTargetCount(ServerLevel level, ChunkPoint chunk) {
            int count = 0;
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            for (int sectionOffset = 0; sectionOffset < this.sectionHeight; sectionOffset++) {
                int sectionY = this.startSectionY + sectionOffset;
                for (int ordinal = 0; ordinal < this.cellsPerSection; ordinal++) {
                    int localIndex = this.localIndexAt(ordinal);
                    this.setWorldPos(mutable, chunk, sectionY, localIndex);
                    if (!level.getBlockState(mutable).isAir()) {
                        count += 1;
                    }
                }
            }
            return count;
        }

        private void forEachTargetCell(ServerLevel level, TargetCellConsumer consumer) {
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            for (int chunkIndex = 0; chunkIndex < this.chunkCount(); chunkIndex++) {
                ChunkPoint chunk = this.chunkAt(chunkIndex);
                level.getChunk(chunk.x(), chunk.z());
                for (int sectionOffset = 0; sectionOffset < this.sectionHeight; sectionOffset++) {
                    int sectionY = this.startSectionY + sectionOffset;
                    for (int ordinal = 0; ordinal < this.cellsPerSection; ordinal++) {
                        this.setWorldPos(mutable, chunk, sectionY, this.localIndexAt(ordinal));
                        consumer.accept(mutable.immutable());
                    }
                }
            }
        }

        private int localIndexAt(int ordinal) {
            if (this.cellsPerSection == SectionChangeMask.ENTRY_COUNT) {
                return ordinal;
            }
            return sparseLocalIndex(ordinal);
        }

        private BlockPos worldPos(ChunkPoint chunk, int sectionY, int localIndex) {
            return new BlockPos(
                    (chunk.x() << 4) + SectionChangeMask.localX(localIndex),
                    (sectionY << 4) + SectionChangeMask.localY(localIndex),
                    (chunk.z() << 4) + SectionChangeMask.localZ(localIndex)
            );
        }

        private void setWorldPos(BlockPos.MutableBlockPos mutable, ChunkPoint chunk, int sectionY, int localIndex) {
            mutable.set(
                    (chunk.x() << 4) + SectionChangeMask.localX(localIndex),
                    (sectionY << 4) + SectionChangeMask.localY(localIndex),
                    (chunk.z() << 4) + SectionChangeMask.localZ(localIndex)
            );
        }

        private static int sparseLocalIndex(int ordinal) {
            return Math.floorMod(ordinal * 67, SectionChangeMask.ENTRY_COUNT);
        }
    }

    private String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    record StepResult(OperationHandle operationHandle, List<String> messages, boolean finished) {

        StepResult {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }

        private static StepResult idle() {
            return new StepResult(null, List.of(), false);
        }
    }

    record DiagnosticCheck(String label, boolean passed, String detail) {
    }

    private record OperationMetric(String label, int totalUnits, long durationMillis, String metrics) {
    }

    private record RunStep(
            OperationHandle operationHandle,
            List<String> messages,
            boolean finished,
            boolean skipped,
            String scenarioName,
            String detail
    ) {

        private RunStep {
            messages = messages == null ? List.of() : List.copyOf(messages);
            scenarioName = scenarioName == null ? "" : scenarioName;
            detail = detail == null ? "" : detail;
        }

        private static RunStep messages(List<String> messages) {
            return new RunStep(null, messages, false, false, "", "");
        }

        private static RunStep operation(OperationHandle handle, String message) {
            return new RunStep(handle, List.of(message), false, false, "", "");
        }

        private static RunStep finished(String scenarioName, String detail) {
            return new RunStep(null, List.of(), true, false, scenarioName, detail);
        }

        private static RunStep skipped(String scenarioName, String detail) {
            return new RunStep(null, List.of(), true, true, scenarioName, detail);
        }
    }

    private enum Stage {
        PREFLIGHT,
        START_FILL,
        START_DELETE,
        VERIFY_DELETE,
        FINISHED
    }

    @FunctionalInterface
    private interface ChunkConsumer {
        void accept(ChunkPoint chunk);
    }

    @FunctionalInterface
    private interface TargetCellConsumer {
        void accept(BlockPos pos);
    }
}
