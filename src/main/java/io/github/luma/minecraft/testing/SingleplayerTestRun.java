package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.RestorePlanMode;
import io.github.luma.domain.model.VersionDiff;
import io.github.luma.domain.service.DiffService;
import io.github.luma.domain.service.HistoryShareService;
import io.github.luma.domain.service.MaterialDeltaService;
import io.github.luma.domain.service.ProjectArchiveService;
import io.github.luma.domain.service.ProjectCleanupService;
import io.github.luma.domain.service.ProjectIntegrityService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.RestoreService;
import io.github.luma.domain.service.UndoRedoService;
import io.github.luma.domain.service.VariantService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.nio.file.Files;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Tick-driven smoke and regression suite for real singleplayer Lumi workflows.
 */
final class SingleplayerTestRun {

    private static final String ACTOR = "Lumi singleplayer testing";

    private final String serverKey;
    private final ServerLevel level;
    private final ServerPlayer player;
    private final SingleplayerTestVolume volume;
    private final ProjectService projectService = new ProjectService();
    private final VersionService versionService = new VersionService();
    private final RestoreService restoreService = new RestoreService();
    private final UndoRedoService undoRedoService = new UndoRedoService();
    private final VariantService variantService = new VariantService();
    private final DiffService diffService = new DiffService();
    private final RecoveryService recoveryService = new RecoveryService();
    private final ProjectIntegrityService integrityService = new ProjectIntegrityService();
    private final ProjectCleanupService cleanupService = new ProjectCleanupService();
    private final ProjectArchiveService archiveService = new ProjectArchiveService();
    private final HistoryShareService shareService = new HistoryShareService();
    private final MaterialDeltaService materialDeltaService = new MaterialDeltaService();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    private Phase phase = Phase.CREATE_PROJECT;
    private OperationHandle pendingOperation;
    private BuildProject project;
    private ProjectVariant branch;
    private int checks;
    private boolean done;

    SingleplayerTestRun(MinecraftServer server, ServerLevel level, ServerPlayer player, SingleplayerTestVolume volume) {
        this.serverKey = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
        this.level = level;
        this.player = player;
        this.volume = volume;
    }

    boolean matches(MinecraftServer server) {
        return this.serverKey.equals(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString());
    }

    boolean done() {
        return this.done;
    }

    String describeVolume() {
        return this.format(this.volume.min()) + " -> " + this.format(this.volume.max());
    }

    void message(MinecraftServer server, String text) {
        ServerPlayer target = server.getPlayerList().getPlayer(this.player.getUUID());
        SingleplayerTestingService.send(target, text);
    }

    void tick(MinecraftServer server) throws Exception {
        if (this.done || this.waitingForOperation(server)) {
            return;
        }

        switch (this.phase) {
            case CREATE_PROJECT -> this.createProject(server);
            case CAPTURE_DRAFT -> this.captureDraft(server);
            case START_UNDO -> this.startUndo();
            case CHECK_UNDO -> this.checkUndo();
            case START_REDO -> this.startRedo();
            case CHECK_REDO -> this.checkRedo();
            case START_SAVE -> this.startSave();
            case CHECK_SAVE -> this.checkSave(server);
            case START_AMEND -> this.startAmend();
            case CHECK_AMEND -> this.checkAmend(server);
            case START_BRANCH_SAVE -> this.startBranchSave(server);
            case CHECK_BRANCH_SAVE -> this.checkBranchSave(server);
            case START_PARTIAL_RESTORE -> this.startPartialRestore();
            case CHECK_PARTIAL_RESTORE -> this.checkPartialRestore(server);
            case START_RESTORE_INITIAL -> this.startRestoreInitial();
            case CHECK_RESTORE_INITIAL -> this.checkRestoreInitial(server);
            case CLEANUP -> this.finish(server);
        }
    }

    void fail(MinecraftServer server, Exception exception) {
        try {
            this.clearVolume();
            if (this.project != null) {
                HistoryCaptureManager.getInstance().discardSession(server, this.project.id().toString());
                this.projectService.setArchived(server, this.project.name(), true);
            }
        } catch (Exception cleanupException) {
            exception.addSuppressed(cleanupException);
        }
        this.message(server, "Lumi singleplayer testing failed in " + this.phase + ": " + exception.getMessage());
    }

    private void createProject(MinecraftServer server) throws Exception {
        String projectName = "Lumi Testing Singleplayer " + System.currentTimeMillis();
        this.project = this.projectService.createProject(this.level, projectName, this.volume.min(), this.volume.max(), ACTOR);
        this.check(this.projectService.loadVersions(server, projectName).size() == 1, "initial version exists");
        this.check(this.projectService.loadVariants(server, projectName).size() == 1, "main branch exists");
        this.check(this.integrityService.inspect(server, projectName).valid(), "new project integrity is valid");
        this.message(server, "Lumi testing: project, initial snapshot, branch metadata, and integrity passed");
        this.phase = Phase.CAPTURE_DRAFT;
    }

    private void captureDraft(MinecraftServer server) throws Exception {
        WorldMutationContext.pushPlayerSource(io.github.luma.domain.model.WorldMutationSource.PLAYER, ACTOR, true);
        try {
            this.level.setBlock(this.volume.markerA(), Blocks.STONE.defaultBlockState(), 3);
            this.level.setBlock(this.volume.markerB(), Blocks.BARREL.defaultBlockState(), 3);
            this.level.setBlock(this.volume.markerC(), Blocks.GLASS.defaultBlockState(), 3);
        } finally {
            WorldMutationContext.popSource();
        }

        var draft = this.recoveryService.loadDraft(server, this.project.name()).orElseThrow();
        this.check(draft.totalChangeCount() >= 3, "recovery draft captured block and block-entity changes");
        VersionDiff currentDiff = this.diffService.compareVersionToCurrentState(server, this.project.name(), ProjectService.versionId(1));
        this.check(currentDiff.changedBlockCount() >= 3, "current-state diff includes pending draft");
        this.check(!this.materialDeltaService.summarize(currentDiff).isEmpty(), "material delta summarizes pending draft");
        this.message(server, "Lumi testing: capture, recovery draft, current diff, and material delta passed");
        this.phase = Phase.START_UNDO;
    }

    private void startUndo() throws Exception {
        this.pendingOperation = this.undoRedoService.undo(this.level, this.project.name());
        this.phase = Phase.CHECK_UNDO;
    }

    private void checkUndo() {
        this.checkAir(this.volume.markerA(), "undo restored marker A");
        this.checkAir(this.volume.markerB(), "undo restored marker B");
        this.checkAir(this.volume.markerC(), "undo restored marker C");
        this.message(this.level.getServer(), "Lumi testing: live undo passed");
        this.phase = Phase.START_REDO;
    }

    private void startRedo() throws Exception {
        this.pendingOperation = this.undoRedoService.redo(this.level, this.project.name());
        this.phase = Phase.CHECK_REDO;
    }

    private void checkRedo() {
        this.checkBlock(this.volume.markerA(), Blocks.STONE, "redo restored stone");
        this.checkBlock(this.volume.markerB(), Blocks.BARREL, "redo restored barrel");
        this.checkBlock(this.volume.markerC(), Blocks.GLASS, "redo restored glass");
        this.message(this.level.getServer(), "Lumi testing: live redo passed");
        this.phase = Phase.START_SAVE;
    }

    private void startSave() throws Exception {
        this.pendingOperation = this.versionService.startSaveVersion(this.level, this.project.name(), "Singleplayer test save", ACTOR);
        this.phase = Phase.CHECK_SAVE;
    }

    private void checkSave(MinecraftServer server) throws Exception {
        this.check(this.projectService.loadVersions(server, this.project.name()).size() == 2, "manual save created v0002");
        VersionDiff diff = this.diffService.compareVersionToParent(server, this.project.name(), ProjectService.versionId(2));
        this.check(diff.changedBlockCount() >= 3, "saved version has patch changes");
        this.check(this.recoveryService.loadDraft(server, this.project.name()).isEmpty(), "save consumed recovery draft");
        this.check(this.cleanupService.inspect(server, this.project.name()).dryRun(), "cleanup inspect is dry-run");
        this.message(server, "Lumi testing: save, patch diff, draft isolation, and cleanup inspect passed");
        this.phase = Phase.START_AMEND;
    }

    private void startAmend() throws Exception {
        WorldMutationContext.pushPlayerSource(io.github.luma.domain.model.WorldMutationSource.PLAYER, ACTOR, true);
        try {
            this.level.setBlock(this.volume.markerC(), Blocks.OAK_PLANKS.defaultBlockState(), 3);
            this.level.setBlock(this.volume.markerD(), Blocks.COPPER_BLOCK.defaultBlockState(), 3);
        } finally {
            WorldMutationContext.popSource();
        }
        this.pendingOperation = this.versionService.startAmendVersion(this.level, this.project.name(), "Singleplayer test amend", ACTOR);
        this.phase = Phase.CHECK_AMEND;
    }

    private void checkAmend(MinecraftServer server) throws Exception {
        this.check(this.projectService.loadVersions(server, this.project.name()).size() == 2, "amend kept version count");
        VersionDiff diff = this.diffService.compareVersionToParent(server, this.project.name(), ProjectService.versionId(2));
        this.check(diff.changedBlockCount() >= 4, "amended version merged new changes");
        this.checkBlock(this.volume.markerC(), Blocks.OAK_PLANKS, "amended world has oak planks");
        this.checkBlock(this.volume.markerD(), Blocks.COPPER_BLOCK, "amended world has copper block");
        this.message(server, "Lumi testing: amend passed");
        this.phase = Phase.START_BRANCH_SAVE;
    }

    private void startBranchSave(MinecraftServer server) throws Exception {
        this.branch = this.variantService.createVariant(server, this.project.name(), "Testing branch", "");
        this.variantService.switchVariant(this.level, this.project.name(), this.branch.id(), false);
        WorldMutationContext.pushPlayerSource(io.github.luma.domain.model.WorldMutationSource.PLAYER, ACTOR, true);
        try {
            this.level.setBlock(this.volume.markerA(), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        } finally {
            WorldMutationContext.popSource();
        }
        this.pendingOperation = this.versionService.startSaveVersion(this.level, this.project.name(), "Singleplayer test branch save", ACTOR);
        this.phase = Phase.CHECK_BRANCH_SAVE;
    }

    private void checkBranchSave(MinecraftServer server) throws Exception {
        this.project = this.projectService.loadProject(server, this.project.name());
        this.check(this.branch.id().equals(this.project.activeVariantId()), "test branch is active");
        this.check(this.projectService.loadVersions(server, this.project.name()).size() == 3, "branch save created v0003");
        this.check(this.diffService.compareVersions(server, this.project.name(), ProjectService.versionId(2), ProjectService.versionId(3)).changedBlockCount() >= 1, "branch diff is non-empty");
        var projectArchive = this.archiveService.exportProject(server, this.project.name(), false);
        var branchArchive = this.shareService.exportVariantPackage(server, this.project.name(), this.branch.id(), false);
        this.check(Files.exists(projectArchive.archiveFile()) && !projectArchive.manifest().entries().isEmpty(), "project archive exported");
        this.check(Files.exists(branchArchive.archiveFile()) && branchArchive.manifest().scopeOrDefault().variantScope(), "branch archive exported");
        Files.deleteIfExists(projectArchive.archiveFile());
        Files.deleteIfExists(branchArchive.archiveFile());
        this.message(server, "Lumi testing: branch, compare, project export, and branch export passed");
        this.phase = Phase.START_PARTIAL_RESTORE;
    }

    private void startPartialRestore() throws Exception {
        PartialRestoreRequest request = this.partialRestoreRequest(ProjectService.versionId(2), this.volume.markerA());
        var plan = this.restoreService.summarizePartialRestorePlan(this.level, request);
        this.check(plan.mode() != RestorePlanMode.NO_OP && plan.changedBlocks() >= 1, "partial restore plan is actionable");
        this.pendingOperation = this.restoreService.partialRestore(this.level, request);
        this.phase = Phase.CHECK_PARTIAL_RESTORE;
    }

    private void checkPartialRestore(MinecraftServer server) throws Exception {
        this.checkBlock(this.volume.markerA(), Blocks.STONE, "partial restore reverted marker A");
        this.check(this.projectService.loadVersions(server, this.project.name()).size() == 4, "partial restore wrote v0004");
        this.message(server, "Lumi testing: partial restore passed");
        this.phase = Phase.START_RESTORE_INITIAL;
    }

    private void startRestoreInitial() throws Exception {
        var plan = this.restoreService.summarizeRestorePlan(this.level, this.project.name(), ProjectService.versionId(1));
        this.check(plan.mode() != RestorePlanMode.NO_OP, "initial restore plan is actionable");
        this.pendingOperation = this.restoreService.restore(this.level, this.project.name(), ProjectService.versionId(1));
        this.phase = Phase.CHECK_RESTORE_INITIAL;
    }

    private void checkRestoreInitial(MinecraftServer server) throws Exception {
        this.check(this.volume.isAir(this.level), "full restore returned test volume to initial air");
        this.check(this.integrityService.inspect(server, this.project.name()).valid(), "final project integrity is valid");
        this.phase = Phase.CLEANUP;
    }

    private void finish(MinecraftServer server) throws Exception {
        this.clearVolume();
        HistoryCaptureManager.getInstance().discardSession(server, this.project.id().toString());
        this.projectService.setArchived(server, this.project.name(), true);
        this.done = true;
        this.message(server, "Lumi singleplayer testing passed " + this.checks + " checks. Temporary project archived: " + this.project.name());
    }

    private boolean waitingForOperation(MinecraftServer server) {
        if (this.pendingOperation == null) {
            return false;
        }
        var snapshot = this.worldOperationManager.snapshot(server, this.pendingOperation.projectId())
                .filter(candidate -> candidate.handle() != null)
                .filter(candidate -> this.pendingOperation.id().equals(candidate.handle().id()));
        if (snapshot.isEmpty()) {
            if (this.worldOperationManager.hasActiveOperation(server)) {
                return true;
            }
            throw new IllegalStateException("World operation disappeared before completion: " + this.pendingOperation.label());
        }
        OperationSnapshot operation = snapshot.get();
        if (!operation.terminal()) {
            return true;
        }
        if (operation.failed()) {
            throw new IllegalStateException(operation.detail());
        }
        this.pendingOperation = null;
        return false;
    }

    private PartialRestoreRequest partialRestoreRequest(String targetVersionId, BlockPos pos) {
        return new PartialRestoreRequest(
                this.project.name(),
                targetVersionId,
                io.github.luma.domain.model.Bounds3i.of(pos, pos),
                PartialRestoreRegionSource.MANUAL_BOUNDS,
                ACTOR,
                Map.of("source", "singleplayer-testing")
        );
    }

    private void clearVolume() {
        WorldMutationContext.runWithSource(io.github.luma.domain.model.WorldMutationSource.RESTORE, () -> {
            for (BlockPos pos : BlockPos.betweenClosed(this.volume.min(), this.volume.max())) {
                this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        });
    }

    private void checkBlock(BlockPos pos, Block block, String label) {
        this.check(this.level.getBlockState(pos).is(block), label);
    }

    private void checkAir(BlockPos pos, String label) {
        this.check(this.level.getBlockState(pos).isAir(), label);
    }

    private void check(boolean condition, String label) {
        if (!condition) {
            throw new IllegalStateException("Check failed: " + label);
        }
        this.checks += 1;
    }

    private String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private enum Phase {
        CREATE_PROJECT,
        CAPTURE_DRAFT,
        START_UNDO,
        CHECK_UNDO,
        START_REDO,
        CHECK_REDO,
        START_SAVE,
        CHECK_SAVE,
        START_AMEND,
        CHECK_AMEND,
        START_BRANCH_SAVE,
        CHECK_BRANCH_SAVE,
        START_PARTIAL_RESTORE,
        CHECK_PARTIAL_RESTORE,
        START_RESTORE_INITIAL,
        CHECK_RESTORE_INITIAL,
        CLEANUP
    }
}
