package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.VersionDiff;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.DiffService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RestoreService;
import io.github.luma.domain.service.VariantService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.SectionChangeMask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Exercises large persisted save, restore, and branch workflows.
 */
final class SingleplayerLargeHistoryScenario {

    private static final int CHUNK_WIDTH = 4;
    private static final int CHUNK_DEPTH = 4;
    private static final int MAIN_SECTION_HEIGHT = 4;
    private static final int BRANCH_SECTION_HEIGHT = 1;
    private static final int CELLS_PER_TICK = 4096;
    private static final int PROGRESS_STEP = 32_768;

    private final ServerLevel level;
    private final BlockPos near;
    private final String actor;
    private final ProjectService projectService = new ProjectService();
    private final VersionService versionService = new VersionService();
    private final RestoreService restoreService = new RestoreService();
    private final VariantService variantService = new VariantService();
    private final DiffService diffService = new DiffService();
    private final Map<String, String> metricKeysByOperationId = new LinkedHashMap<>();
    private final Map<String, String> operationCompletions = new LinkedHashMap<>();
    private final Map<String, String> metrics = new LinkedHashMap<>();
    private final List<DiagnosticCheck> checks = new ArrayList<>();

    private Stage stage = Stage.PREFLIGHT;
    private BuildProject project;
    private ProjectVariant branch;
    private int startChunkX;
    private int startChunkZ;
    private int startY;
    private int cursor;
    private int nextProgress = PROGRESS_STEP;
    private int mismatchCount;
    private BlockPos firstOccupied;
    private boolean finished;
    private boolean cleanedUp;

    SingleplayerLargeHistoryScenario(ServerLevel level, BlockPos near, String actor) {
        this.level = level;
        this.near = near == null ? BlockPos.ZERO : near.immutable();
        this.actor = actor == null || actor.isBlank() ? "Lumi large history test" : actor;
        int baseChunkX = Math.floorDiv(this.near.getX(), 16);
        int baseChunkZ = Math.floorDiv(this.near.getZ(), 16);
        this.startChunkX = baseChunkX + 8;
        this.startChunkZ = baseChunkZ + 8;
        this.startY = Math.floorDiv(level.getMaxY() - 80, 16) << 4;
    }

    StepResult advance(MinecraftServer server) {
        if (this.finished) {
            return StepResult.idle();
        }
        return switch (this.stage) {
            case PREFLIGHT -> this.preflight();
            case CREATE_PROJECT -> this.createProject(server);
            case PLACE_MAIN -> this.placeMain();
            case START_SAVE_MAIN -> this.startSaveMain();
            case CHECK_SAVE_MAIN -> this.checkSaveMain(server);
            case CREATE_BRANCH -> this.createBranch(server);
            case PLACE_BRANCH -> this.placeBranch();
            case START_SAVE_BRANCH -> this.startSaveBranch();
            case CHECK_SAVE_BRANCH -> this.checkSaveBranch(server);
            case START_RESTORE_MAIN -> this.startRestoreMain();
            case VERIFY_RESTORE_MAIN -> this.verifyRestoreMain(server);
            case START_RESTORE_BRANCH -> this.startRestoreBranch();
            case VERIFY_RESTORE_BRANCH -> this.verifyRestoreBranch(server);
            case CLEANUP_BLOCKS -> this.cleanupBlocks();
            case FINISHED -> StepResult.finished(this.summaryLines());
        };
    }

    void recordMetrics(OperationHandle handle, OperationSnapshot snapshot, String metricSummary) {
        if (handle == null || snapshot == null || metricSummary == null || metricSummary.isBlank()) {
            return;
        }
        String key = this.metricKeysByOperationId.get(handle.id());
        if (key != null) {
            this.metrics.put(key, metricSummary);
        }
    }

    void recordCompletion(OperationHandle handle, OperationSnapshot snapshot) {
        if (handle == null || snapshot == null) {
            return;
        }
        String key = this.metricKeysByOperationId.get(handle.id());
        if (key != null) {
            long durationMs = Duration.between(handle.startedAt(), snapshot.updatedAt()).toMillis();
            this.operationCompletions.put(key, "durationMs=" + Math.max(0L, durationMs)
                    + ", units=" + snapshot.progress().completedUnits());
        }
    }

    List<DiagnosticCheck> checks() {
        return List.copyOf(this.checks);
    }

    void cleanup() {
        if (this.project == null || this.cleanedUp) {
            return;
        }
        WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
            for (int index = 0; index < this.mainCellCount(); index++) {
                this.level.setBlock(this.cellPos(index, MAIN_SECTION_HEIGHT), Blocks.AIR.defaultBlockState(), 3);
            }
        });
        try {
            HistoryCaptureManager.getInstance().discardSession(this.level.getServer(), this.project.id().toString());
            this.projectService.setArchived(this.level.getServer(), this.project.name(), true);
            this.cleanedUp = true;
        } catch (Exception ignored) {
        }
    }

    private StepResult preflight() {
        List<String> messages = new ArrayList<>();
        int processed = 0;
        while (processed < CELLS_PER_TICK && this.cursor < this.mainCellCount()) {
            BlockPos pos = this.cellPos(this.cursor, MAIN_SECTION_HEIGHT);
            this.level.getChunk(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
            if (this.firstOccupied == null && !this.level.getBlockState(pos).isAir()) {
                this.firstOccupied = pos;
            }
            this.cursor += 1;
            processed += 1;
        }
        this.addProgress(messages, "preflight", this.mainCellCount());
        if (this.firstOccupied != null) {
            this.checks.add(new DiagnosticCheck(
                    "Large history target volume starts empty",
                    false,
                    "firstOccupied=" + this.format(this.firstOccupied)
            ));
            this.finished = true;
            return StepResult.finished(this.summaryLines());
        }
        if (this.cursor >= this.mainCellCount()) {
            this.resetCursor();
            this.stage = Stage.CREATE_PROJECT;
            messages.add("Large history preflight complete: targetCells=" + this.mainCellCount());
        }
        return StepResult.messages(messages);
    }

    private StepResult createProject(MinecraftServer server) {
        try {
            String name = "Lumi Large History " + System.currentTimeMillis();
            this.project = this.projectService.createProject(
                    this.level,
                    name,
                    this.cellPos(0, MAIN_SECTION_HEIGHT),
                    this.cellPos(this.mainCellCount() - 1, MAIN_SECTION_HEIGHT),
                    this.actor
            );
            this.stage = Stage.PLACE_MAIN;
            return StepResult.messages(List.of("Large history project created: " + name));
        } catch (Exception exception) {
            this.checks.add(new DiagnosticCheck("Large history project can be created", false, this.errorMessage(exception)));
            this.finished = true;
            return StepResult.finished(this.summaryLines());
        }
    }

    private StepResult placeMain() {
        List<String> messages = new ArrayList<>();
        this.placeCells(MAIN_SECTION_HEIGHT, this.mainCellCount(), Blocks.STONE.defaultBlockState(), messages, "main fill");
        if (this.cursor >= this.mainCellCount()) {
            this.resetCursor();
            this.stage = Stage.START_SAVE_MAIN;
            messages.add("Large history main fill complete: cells=" + this.mainCellCount());
        }
        return StepResult.messages(messages);
    }

    private StepResult startSaveMain() {
        try {
            this.stage = Stage.CHECK_SAVE_MAIN;
            OperationHandle handle = this.versionService.startSaveVersion(
                    this.level,
                    this.project.name(),
                    "Large persisted main save",
                    this.actor
            );
            this.metricKeysByOperationId.put(handle.id(), "main-save");
            return StepResult.operation(handle, "Queued large main save with " + this.mainCellCount() + " changed cells");
        } catch (Exception exception) {
            this.checks.add(new DiagnosticCheck("Large main save can be queued", false, this.errorMessage(exception)));
            this.finished = true;
            return StepResult.finished(this.summaryLines());
        }
    }

    private StepResult checkSaveMain(MinecraftServer server) {
        try {
            this.checks.add(new DiagnosticCheck(
                    "Large main save wrote version v0002",
                    this.projectService.loadVersions(server, this.project.name()).size() >= 2,
                    "version=v0002"
            ));
            VersionDiff diff = this.diffService.compareVersionToParent(server, this.project.name(), ProjectService.versionId(2));
            this.checks.add(new DiagnosticCheck(
                    "Large main save persisted expected block count",
                    diff.changedBlockCount() >= this.mainCellCount(),
                    "changedBlocks=" + diff.changedBlockCount()
            ));
            this.checks.add(new DiagnosticCheck(
                    "Large main save operation completed",
                    this.operationCompletions.containsKey("main-save"),
                    this.operationCompletions.getOrDefault("main-save", "missing")
            ));
            this.stage = Stage.CREATE_BRANCH;
            return StepResult.messages(List.of("Large main save verified"));
        } catch (Exception exception) {
            this.checks.add(new DiagnosticCheck("Large main save can be verified", false, this.errorMessage(exception)));
            this.finished = true;
            return StepResult.finished(this.summaryLines());
        }
    }

    private StepResult createBranch(MinecraftServer server) {
        try {
            this.branch = this.variantService.createVariant(server, this.project.name(), "Large branch", ProjectService.versionId(2));
            this.variantService.switchVariant(this.level, this.project.name(), this.branch.id(), false);
            this.stage = Stage.PLACE_BRANCH;
            return StepResult.messages(List.of("Large branch created: " + this.branch.id()));
        } catch (Exception exception) {
            this.checks.add(new DiagnosticCheck("Large branch can be created", false, this.errorMessage(exception)));
            this.finished = true;
            return StepResult.finished(this.summaryLines());
        }
    }

    private StepResult placeBranch() {
        List<String> messages = new ArrayList<>();
        this.placeCells(BRANCH_SECTION_HEIGHT, this.branchCellCount(), Blocks.GOLD_BLOCK.defaultBlockState(), messages, "branch fill");
        if (this.cursor >= this.branchCellCount()) {
            this.resetCursor();
            this.stage = Stage.START_SAVE_BRANCH;
            messages.add("Large branch fill complete: cells=" + this.branchCellCount());
        }
        return StepResult.messages(messages);
    }

    private StepResult startSaveBranch() {
        try {
            this.stage = Stage.CHECK_SAVE_BRANCH;
            OperationHandle handle = this.versionService.startSaveVersion(
                    this.level,
                    this.project.name(),
                    "Large persisted branch save",
                    this.actor
            );
            this.metricKeysByOperationId.put(handle.id(), "branch-save");
            return StepResult.operation(handle, "Queued large branch save with " + this.branchCellCount() + " changed cells");
        } catch (Exception exception) {
            this.checks.add(new DiagnosticCheck("Large branch save can be queued", false, this.errorMessage(exception)));
            this.finished = true;
            return StepResult.finished(this.summaryLines());
        }
    }

    private StepResult checkSaveBranch(MinecraftServer server) {
        try {
            VersionDiff diff = this.diffService.compareVersions(
                    server,
                    this.project.name(),
                    ProjectService.versionId(2),
                    ProjectService.versionId(3)
            );
            this.checks.add(new DiagnosticCheck(
                    "Large branch save persisted divergent block count",
                    diff.changedBlockCount() >= this.branchCellCount(),
                    "changedBlocks=" + diff.changedBlockCount()
            ));
            this.checks.add(new DiagnosticCheck(
                    "Large branch save operation completed",
                    this.operationCompletions.containsKey("branch-save"),
                    this.operationCompletions.getOrDefault("branch-save", "missing")
            ));
            this.stage = Stage.START_RESTORE_MAIN;
            return StepResult.messages(List.of("Large branch save verified"));
        } catch (Exception exception) {
            this.checks.add(new DiagnosticCheck("Large branch save can be verified", false, this.errorMessage(exception)));
            this.finished = true;
            return StepResult.finished(this.summaryLines());
        }
    }

    private StepResult startRestoreMain() {
        try {
            this.stage = Stage.VERIFY_RESTORE_MAIN;
            OperationHandle handle = this.restoreService.restore(this.level, this.project.name(), ProjectService.versionId(2));
            this.metricKeysByOperationId.put(handle.id(), "restore-main");
            return StepResult.operation(handle, "Queued large restore back to main version v0002");
        } catch (Exception exception) {
            this.checks.add(new DiagnosticCheck("Large restore to main can be queued", false, this.errorMessage(exception)));
            this.finished = true;
            return StepResult.finished(this.summaryLines());
        }
    }

    private StepResult verifyRestoreMain(MinecraftServer server) {
        List<String> messages = new ArrayList<>();
        this.verifyCells(BRANCH_SECTION_HEIGHT, this.branchCellCount(), Blocks.STONE.defaultBlockState(), messages, "main restore verify");
        if (this.cursor >= this.branchCellCount()) {
            boolean activeMain = this.activeVariantId(server).equals("main");
            this.checks.add(new DiagnosticCheck(
                    "Large restore to main restored branch cells to stone",
                    this.mismatchCount == 0,
                    "mismatches=" + this.mismatchCount
            ));
            this.checks.add(new DiagnosticCheck(
                    "Large restore to main activated main branch",
                    activeMain,
                    "activeVariant=" + this.activeVariantId(server)
            ));
            this.checks.add(new DiagnosticCheck(
                    "Large restore to main metrics were recorded",
                    this.metrics.containsKey("restore-main"),
                    "metric=restore-main"
            ));
            this.resetCursor();
            this.stage = Stage.START_RESTORE_BRANCH;
            messages.add("Large restore to main verified");
        }
        return StepResult.messages(messages);
    }

    private StepResult startRestoreBranch() {
        try {
            this.stage = Stage.VERIFY_RESTORE_BRANCH;
            OperationHandle handle = this.restoreService.restoreVariantHead(this.level, this.project.name(), this.branch.id());
            this.metricKeysByOperationId.put(handle.id(), "restore-branch");
            return StepResult.operation(handle, "Queued large restore to branch head v0003");
        } catch (Exception exception) {
            this.checks.add(new DiagnosticCheck("Large restore to branch can be queued", false, this.errorMessage(exception)));
            this.finished = true;
            return StepResult.finished(this.summaryLines());
        }
    }

    private StepResult verifyRestoreBranch(MinecraftServer server) {
        List<String> messages = new ArrayList<>();
        this.verifyCells(BRANCH_SECTION_HEIGHT, this.branchCellCount(), Blocks.GOLD_BLOCK.defaultBlockState(), messages, "branch restore verify");
        if (this.cursor >= this.branchCellCount()) {
            boolean activeBranch = this.activeVariantId(server).equals(this.branch.id());
            this.checks.add(new DiagnosticCheck(
                    "Large restore to branch restored divergent cells to gold",
                    this.mismatchCount == 0,
                    "mismatches=" + this.mismatchCount
            ));
            this.checks.add(new DiagnosticCheck(
                    "Large restore to branch activated branch",
                    activeBranch,
                    "activeVariant=" + this.activeVariantId(server)
            ));
            this.checks.add(new DiagnosticCheck(
                    "Large restore to branch metrics were recorded",
                    this.metrics.containsKey("restore-branch"),
                    "metric=restore-branch"
            ));
            this.resetCursor();
            this.stage = Stage.CLEANUP_BLOCKS;
            messages.add("Large restore to branch verified");
        }
        return StepResult.messages(messages);
    }

    private StepResult cleanupBlocks() {
        List<String> messages = new ArrayList<>();
        WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
            int processed = 0;
            while (processed < CELLS_PER_TICK && this.cursor < this.mainCellCount()) {
                this.level.setBlock(this.cellPos(this.cursor, MAIN_SECTION_HEIGHT), Blocks.AIR.defaultBlockState(), 3);
                this.cursor += 1;
                processed += 1;
            }
        });
        this.addProgress(messages, "cleanup", this.mainCellCount());
        if (this.cursor >= this.mainCellCount()) {
            try {
                HistoryCaptureManager.getInstance().discardSession(this.level.getServer(), this.project.id().toString());
                this.projectService.setArchived(this.level.getServer(), this.project.name(), true);
                this.cleanedUp = true;
                this.checks.add(new DiagnosticCheck("Large history temporary project was archived", true, this.project.name()));
            } catch (Exception exception) {
                this.checks.add(new DiagnosticCheck("Large history temporary project was archived", false, this.errorMessage(exception)));
            }
            this.finished = true;
            this.stage = Stage.FINISHED;
            messages.addAll(this.summaryLines());
            return StepResult.finished(messages);
        }
        return StepResult.messages(messages);
    }

    private void placeCells(int sectionHeight, int totalCells, BlockState state, List<String> messages, String label) {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, this.actor, true);
        try {
            int processed = 0;
            while (processed < CELLS_PER_TICK && this.cursor < totalCells) {
                this.level.setBlock(this.cellPos(this.cursor, sectionHeight), state, 3);
                this.cursor += 1;
                processed += 1;
            }
        } finally {
            WorldMutationContext.popSource();
        }
        this.addProgress(messages, label, totalCells);
    }

    private void verifyCells(int sectionHeight, int totalCells, BlockState expected, List<String> messages, String label) {
        int processed = 0;
        while (processed < CELLS_PER_TICK && this.cursor < totalCells) {
            if (!this.level.getBlockState(this.cellPos(this.cursor, sectionHeight)).is(expected.getBlock())) {
                this.mismatchCount += 1;
            }
            this.cursor += 1;
            processed += 1;
        }
        this.addProgress(messages, label, totalCells);
    }

    private void addProgress(List<String> messages, String label, int total) {
        if (this.cursor < this.nextProgress || this.cursor >= total) {
            return;
        }
        messages.add("Large history " + label + ": cells=" + this.cursor + "/" + total);
        this.nextProgress += PROGRESS_STEP;
    }

    private void resetCursor() {
        this.cursor = 0;
        this.nextProgress = PROGRESS_STEP;
        this.mismatchCount = 0;
    }

    private int mainCellCount() {
        return CHUNK_WIDTH * CHUNK_DEPTH * MAIN_SECTION_HEIGHT * SectionChangeMask.ENTRY_COUNT;
    }

    private int branchCellCount() {
        return CHUNK_WIDTH * CHUNK_DEPTH * BRANCH_SECTION_HEIGHT * SectionChangeMask.ENTRY_COUNT;
    }

    private BlockPos cellPos(int index, int sectionHeight) {
        int cellsPerChunk = sectionHeight * SectionChangeMask.ENTRY_COUNT;
        int chunkIndex = index / cellsPerChunk;
        int localChunkIndex = index % cellsPerChunk;
        int sectionOffset = localChunkIndex / SectionChangeMask.ENTRY_COUNT;
        int localIndex = localChunkIndex % SectionChangeMask.ENTRY_COUNT;
        int chunkX = this.startChunkX + (chunkIndex % CHUNK_WIDTH);
        int chunkZ = this.startChunkZ + (chunkIndex / CHUNK_WIDTH);
        return new BlockPos(
                (chunkX << 4) + SectionChangeMask.localX(localIndex),
                this.startY + (sectionOffset << 4) + SectionChangeMask.localY(localIndex),
                (chunkZ << 4) + SectionChangeMask.localZ(localIndex)
        );
    }

    private String activeVariantId(MinecraftServer server) {
        try {
            return this.projectService.loadProject(server, this.project.name()).activeVariantId();
        } catch (Exception exception) {
            return "missing:" + this.errorMessage(exception);
        }
    }

    private List<String> summaryLines() {
        return List.of(
                "Large persisted history summary: mainCells=" + this.mainCellCount()
                        + ", branchCells=" + this.branchCellCount()
                        + ", operations=" + this.operationCompletions
                        + ", metrics=" + this.metrics.keySet(),
                "Large persisted history checks: " + this.checks.size()
        );
    }

    private String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private String errorMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    record StepResult(OperationHandle operationHandle, List<String> messages, boolean finished) {

        StepResult {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }

        private static StepResult idle() {
            return new StepResult(null, List.of(), false);
        }

        private static StepResult messages(List<String> messages) {
            return new StepResult(null, messages, false);
        }

        private static StepResult operation(OperationHandle operationHandle, String message) {
            return new StepResult(operationHandle, List.of(message), false);
        }

        private static StepResult finished(List<String> messages) {
            return new StepResult(null, messages, true);
        }
    }

    record DiagnosticCheck(String label, boolean passed, String detail) {
    }

    private enum Stage {
        PREFLIGHT,
        CREATE_PROJECT,
        PLACE_MAIN,
        START_SAVE_MAIN,
        CHECK_SAVE_MAIN,
        CREATE_BRANCH,
        PLACE_BRANCH,
        START_SAVE_BRANCH,
        CHECK_SAVE_BRANCH,
        START_RESTORE_MAIN,
        VERIFY_RESTORE_MAIN,
        START_RESTORE_BRANCH,
        VERIFY_RESTORE_BRANCH,
        CLEANUP_BLOCKS,
        FINISHED
    }
}
