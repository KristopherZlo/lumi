package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.RestorePlanMode;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.ProjectIntegrityService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.RestoreService;
import io.github.luma.domain.service.VariantService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;

@SuppressWarnings("UnstableApiUsage")
final class HistoryJourneyScenario {

    private static final String ACTOR = "Lumi history journey gametest";
    private static final int OPERATION_TIMEOUT_TICKS = 20 * 180;
    private static final int WORLD_MATCH_TIMEOUT_TICKS = 20 * 60;
    private static final int SETTLE_TICKS = 6;

    private final ClientGameTestContext context;
    private final ProjectService projectService = new ProjectService();
    private final VersionService versionService = new VersionService();
    private final RestoreService restoreService = new RestoreService();
    private final VariantService variantService = new VariantService();
    private final RecoveryService recoveryService = new RecoveryService();
    private final ProjectIntegrityService integrityService = new ProjectIntegrityService();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();
    private final UndoRedoHistoryManager undoRedoHistoryManager = UndoRedoHistoryManager.getInstance();
    private final Map<String, HistoryJourneyCheckpoint> checkpoints = new LinkedHashMap<>();
    private final Map<String, String> versionIds = new LinkedHashMap<>();
    private final Map<String, String> versionVariants = new LinkedHashMap<>();
    private final Map<String, String> branchIds = new LinkedHashMap<>();
    private final Map<String, String> variantHeads = new LinkedHashMap<>();
    private final Map<String, String> variantHeadLabels = new LinkedHashMap<>();

    private TestSingleplayerContext singleplayer;
    private SingleplayerTestVolume volume;
    private String projectName;
    private String projectId;
    private String activeVariantId = "main";
    private int versionCount;
    private int explicitSaveCount;
    private LiveUndoRedoJourney liveUndoRedoJourney;

    HistoryJourneyScenario(ClientGameTestContext context) {
        this.context = context;
    }

    void run(TestSingleplayerContext singleplayer) throws Exception {
        this.singleplayer = singleplayer;
        Exception failure = null;
        try {
            this.createProject();
            this.executeJourney();
            this.assertFinalState();
        } catch (Exception exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                this.cleanup();
            } catch (Exception cleanupException) {
                if (failure == null) {
                    throw cleanupException;
                }
                failure.addSuppressed(cleanupException);
            }
        }
    }

    private void createProject() throws Exception {
        InitialProject initial = this.singleplayer.getServer().computeOnServer(server -> {
            ServerLevel level = server.overworld();
            ServerPlayer player = firstPlayer(server);
            SingleplayerTestVolume foundVolume = SingleplayerTestVolume.find(level, player.blockPosition())
                    .orElseThrow(() -> new IllegalStateException("No empty history journey volume is available"));
            var project = this.projectService.ensureWorldProject(level, ACTOR);
            return new InitialProject(project.id().toString(), project.name(), foundVolume);
        });
        this.projectId = initial.projectId();
        this.projectName = initial.projectName();
        this.volume = initial.volume();
        this.liveUndoRedoJourney = new LiveUndoRedoJourney(
                this.context, this.singleplayer, this.volume, this.projectName, this.projectId
        );
        this.versionCount = 1;
        this.variantHeads.put("main", ProjectService.versionId(1));
        this.variantHeadLabels.put("main", "baseline");
        this.versionIds.put("baseline", ProjectService.versionId(1));
        this.versionVariants.put("baseline", "main");

        HistoryJourneyCheckpoint expected = this.capture("baseline")
                .withProjectState(this.activeVariantId, this.variantHeads(), this.versionCount);
        HistoryJourneyCheckpoint actual = this.capture("baseline actual");
        expected.assertMatches(actual);
        this.checkpoints.put("baseline", expected.withLabel("baseline"));
    }

    private void executeJourney() throws Exception {
        this.liveUndoRedoJourney.run();
        this.placeMainSimpleBlocks();
        this.save("S01-main-simple", "S01 simple main blocks");
        this.liveUndoRedoJourney.verifyQuickRollbackUndoable();

        this.placeMainRedstoneBase();
        this.save("S02-main-redstone-base", "S02 redstone base");

        this.updateMainRedstone();
        this.save("S03-main-redstone-updated", "S03 redstone updated");

        this.createBranch("branch-a", "S03-main-redstone-updated");
        this.createBranch("branch-b", "S02-main-redstone-base");
        this.createBranch("branch-c", "S01-main-simple");
        this.createBranch("branch-d", "S03-main-redstone-updated");

        this.switchVariant("branch-a");
        this.placeBranchASimpleEdit();
        this.save("S04-branch-a-simple", "S04 branch A simple edit");

        this.restoreVersion("S03-main-redstone-updated", "restore branch A back to main S03");
        this.placeMainRollbackEdit();
        this.save("S05-main-after-restore-edit", "S05 main edit after full restore");

        this.switchVariant("branch-a");
        this.placeBranchARedstoneEdit();
        this.save("S06-branch-a-redstone", "S06 branch A redstone edit");

        this.switchVariant("branch-b");
        this.placeBranchBSimpleEdit();
        this.save("S07-branch-b-simple", "S07 branch B simple edit");

        this.partialRestore(
                "P01-branch-b-selected-redstone-restore",
                "S03-main-redstone-updated",
                Bounds3i.of(this.pos(5, 1, 5), this.pos(11, 2, 5))
        );
        this.placeBranchBAfterPartialRestoreEdit();
        this.save("S08-branch-b-after-partial", "S08 branch B after partial restore");

        this.switchVariant("branch-c");
        this.placeBranchCSimpleEdit();
        this.save("S10-branch-c-simple", "S10 branch C simple edit");

        this.placeBranchCRedstoneEdit();
        this.save("S11-branch-c-redstone", "S11 branch C redstone edit");

        this.switchVariant("branch-d");
        this.placeBranchDRedstoneEdit();
        this.save("S12-branch-d-redstone", "S12 branch D redstone edit");

        this.restoreVersion("S03-main-redstone-updated", "restore branch D work back to main S03");
        this.switchVariant("branch-d");
        this.placeBranchDAfterRestoreSwitchEdit();
        this.save("S13-branch-d-after-switch", "S13 branch D after restore-backed switch");

        this.switchVariant("main");
        this.placeMainLateRedstoneEdit();
        this.save("S14-main-redstone-return", "S14 main redstone after branch return");

        this.placeMainFinalMixedEdit();
        this.save("S15-main-final-mixed", "S15 final mixed main edit");
    }

    private void placeMainSimpleBlocks() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            SingleplayerPlayerActionDriver driver = this.driver(server);
            this.withPlayerSource(() -> {
                level.setBlock(this.pos(1, 1, 1), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                level.setBlock(this.pos(3, 1, 1), Blocks.BARREL.defaultBlockState(), 3);
            });
            this.assertPlaced(driver.placeAgainst(this.pos(1, 1, 1), Direction.EAST, Blocks.GLASS, this.pos(2, 1, 1)),
                    "main simple glass");
        });
        this.waitTicks(SETTLE_TICKS);
    }

    private void placeMainRedstoneBase() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            SingleplayerPlayerActionDriver driver = this.driver(server);
            this.withPlayerSource(() -> {
                for (int x = 5; x <= 9; x++) {
                    level.setBlock(this.pos(x, 1, 5), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                }
                level.setBlock(this.pos(6, 2, 5), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
                level.setBlock(this.pos(7, 2, 5), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
                level.setBlock(this.pos(8, 2, 5), Blocks.REPEATER.defaultBlockState(), 3);
                level.setBlock(this.pos(9, 2, 5), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
            });
            this.assertPlaced(driver.placeAgainst(this.pos(5, 1, 5), Direction.UP, Blocks.LEVER, this.pos(5, 2, 5)),
                    "main redstone lever");
        });
        this.waitTicks(SETTLE_TICKS);
    }

    private void updateMainRedstone() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            SingleplayerPlayerActionDriver driver = this.driver(server);
            this.assertPlaced(driver.useBlock(this.pos(5, 2, 5), Direction.UP), "main redstone lever toggle");
            this.withPlayerSource(() -> {
                level.setBlock(this.pos(10, 2, 5), Blocks.OBSERVER.defaultBlockState(), 3);
                level.setBlock(this.pos(11, 2, 5), Blocks.STICKY_PISTON.defaultBlockState(), 3);
            });
        });
        this.waitTicks(SETTLE_TICKS * 2);
    }

    private void placeBranchASimpleEdit() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            SingleplayerPlayerActionDriver driver = this.driver(server);
            this.assertPlaced(driver.destroyBlock(this.pos(3, 1, 1)), "branch A barrel removal");
            this.withPlayerSource(() ->
                    level.setBlock(this.pos(1, 2, 1), Blocks.GOLD_BLOCK.defaultBlockState(), 3));
        });
        this.waitTicks(SETTLE_TICKS);
    }

    private void placeMainRollbackEdit() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            this.withPlayerSource(() -> {
                level.setBlock(this.pos(4, 1, 1), Blocks.COPPER_BLOCK.defaultBlockState(), 3);
                level.setBlock(this.pos(4, 2, 1), Blocks.AMETHYST_BLOCK.defaultBlockState(), 3);
            });
        });
        this.waitTicks(SETTLE_TICKS);
    }

    private void placeBranchARedstoneEdit() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            SingleplayerPlayerActionDriver driver = this.driver(server);
            this.assertPlaced(driver.useBlock(this.pos(5, 2, 5), Direction.UP), "branch A redstone lever toggle");
            this.withPlayerSource(() -> {
                level.setBlock(this.pos(7, 1, 6), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                level.setBlock(this.pos(7, 2, 6), Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
            });
        });
        this.waitTicks(SETTLE_TICKS * 2);
    }

    private void placeBranchBSimpleEdit() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            this.withPlayerSource(() ->
                    level.setBlock(this.pos(2, 2, 2), Blocks.DIAMOND_BLOCK.defaultBlockState(), 3));
        });
        this.waitTicks(SETTLE_TICKS);
    }

    private void placeBranchBAfterPartialRestoreEdit() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            this.withPlayerSource(() ->
                    level.setBlock(this.pos(3, 2, 2), Blocks.EMERALD_BLOCK.defaultBlockState(), 3));
        });
        this.waitTicks(SETTLE_TICKS);
    }

    private void placeBranchCSimpleEdit() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            this.withPlayerSource(() ->
                    level.setBlock(this.pos(12, 1, 2), Blocks.BOOKSHELF.defaultBlockState(), 3));
        });
        this.waitTicks(SETTLE_TICKS);
    }

    private void placeBranchCRedstoneEdit() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            SingleplayerPlayerActionDriver driver = this.driver(server);
            this.withPlayerSource(() -> {
                level.setBlock(this.pos(5, 1, 8), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                level.setBlock(this.pos(6, 1, 8), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                level.setBlock(this.pos(6, 2, 8), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
            });
            this.assertPlaced(driver.placeAgainst(this.pos(5, 1, 8), Direction.UP, Blocks.LEVER, this.pos(5, 2, 8)),
                    "branch C lever");
        });
        this.waitTicks(SETTLE_TICKS);
    }

    private void placeBranchDRedstoneEdit() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            SingleplayerPlayerActionDriver driver = this.driver(server);
            this.assertPlaced(driver.useBlock(this.pos(5, 2, 5), Direction.UP), "branch D redstone lever toggle");
            this.withPlayerSource(() ->
                    level.setBlock(this.pos(12, 2, 5), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3));
        });
        this.waitTicks(SETTLE_TICKS * 2);
    }

    private void placeBranchDAfterRestoreSwitchEdit() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            this.withPlayerSource(() ->
                    level.setBlock(this.pos(13, 1, 3), Blocks.LAPIS_BLOCK.defaultBlockState(), 3));
        });
        this.waitTicks(SETTLE_TICKS);
    }

    private void placeMainLateRedstoneEdit() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            SingleplayerPlayerActionDriver driver = this.driver(server);
            this.assertPlaced(driver.useBlock(this.pos(5, 2, 5), Direction.UP), "main late redstone lever toggle");
            this.withPlayerSource(() ->
                    level.setBlock(this.pos(8, 2, 6), Blocks.COMPARATOR.defaultBlockState(), 3));
        });
        this.waitTicks(SETTLE_TICKS * 2);
    }

    private void placeMainFinalMixedEdit() throws Exception {
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            this.withPlayerSource(() -> {
                level.setBlock(this.pos(14, 1, 4), Blocks.IRON_BLOCK.defaultBlockState(), 3);
                level.setBlock(this.pos(14, 2, 4), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
                level.setBlock(this.pos(15, 1, 4), Blocks.CUT_COPPER.defaultBlockState(), 3);
            });
        });
        this.waitTicks(SETTLE_TICKS);
    }

    private void createBranch(String branchName, String fromLabel) throws Exception {
        HistoryJourneyCheckpoint before = this.capture("expected before create " + branchName);
        String fromVersionId = this.versionIds.get(fromLabel);
        ProjectVariant variant = this.singleplayer.getServer().computeOnServer(server ->
                this.variantService.createVariant(server, this.projectName, branchName, fromVersionId));
        if (!branchName.equals(variant.id())) {
            throw new AssertionError("Unexpected branch id for " + branchName + ": " + variant.id());
        }
        this.branchIds.put(branchName, variant.id());
        this.variantHeads.put(variant.id(), fromVersionId);
        this.variantHeadLabels.put(variant.id(), fromLabel);

        HistoryJourneyCheckpoint expected = before
                .withLabel("create " + branchName)
                .withProjectState(this.activeVariantId, this.variantHeads(), this.versionCount);
        HistoryJourneyCheckpoint actual = this.capture("actual create " + branchName);
        expected.assertMatches(actual);
        this.assertNoDraft("branch creation " + branchName);
    }

    private void switchVariant(String branchName) throws Exception {
        String variantId = "main".equals(branchName) ? "main" : this.branchIds.get(branchName);
        if (variantId == null || variantId.isBlank()) {
            throw new AssertionError("Unknown branch: " + branchName);
        }
        OperationHandle handle = this.singleplayer.getServer().computeOnServer(server -> {
            this.variantService.switchVariant(server.overworld(), this.projectName, variantId);
            return this.worldOperationManager.snapshot(server)
                    .map(OperationSnapshot::handle)
                    .orElseThrow(() -> new IllegalStateException("Variant switch did not start an operation"));
        });
        this.waitForOperation(handle, "switch " + branchName);
        this.assertUndoHistoryCleared("switch " + branchName);
        this.activeVariantId = variantId;

        String headLabel = this.variantHeadLabels.get(variantId);
        HistoryJourneyCheckpoint expected = this.checkpoints.get(headLabel)
                .withLabel("switch " + branchName)
                .withProjectState(this.activeVariantId, this.variantHeads(), this.versionCount);
        HistoryJourneyCheckpoint actual = this.capture("actual switch " + branchName);
        expected.assertMatches(actual);
        this.assertNoDraft("switch " + branchName);
    }

    private void restoreVersion(String targetLabel, String label) throws Exception {
        String versionId = this.versionIds.get(targetLabel);
        OperationHandle handle = this.singleplayer.getServer().computeOnServer(server ->
                this.restoreService.restore(server.overworld(), this.projectName, versionId));
        this.waitForOperation(handle, label);
        this.assertUndoHistoryCleared(label);

        this.versionCount = this.visibleProjectState().versionCount();

        String targetVariant = this.versionVariants.get(targetLabel);
        this.activeVariantId = targetVariant;
        this.variantHeads.put(targetVariant, versionId);
        this.variantHeadLabels.put(targetVariant, targetLabel);

        HistoryJourneyCheckpoint expected = this.checkpoints.get(targetLabel)
                .withLabel(label)
                .withProjectState(this.activeVariantId, this.variantHeads(), this.versionCount);
        HistoryJourneyCheckpoint actual = this.capture("actual " + label);
        expected.assertMatches(actual);
        this.assertNoDraft(label);
    }

    private void partialRestore(String label, String targetLabel, Bounds3i selection) throws Exception {
        HistoryJourneyCheckpoint current = this.capture(label + " current")
                .withProjectState(this.activeVariantId, this.variantHeads(), this.versionCount);
        this.checkpoints.get(this.variantHeadLabels.get(this.activeVariantId)).assertMatches(current);

        String targetVersionId = this.versionIds.get(targetLabel);
        OperationHandle handle = this.singleplayer.getServer().computeOnServer(server -> {
            PartialRestoreRequest request = new PartialRestoreRequest(
                    this.projectName,
                    targetVersionId,
                    selection,
                    PartialRestoreMode.SELECTED_AREA,
                    PartialRestoreRegionSource.MANUAL_BOUNDS,
                    ACTOR,
                    Map.of("source", "history-journey-gametest")
            );
            var plan = this.restoreService.summarizePartialRestorePlan(server.overworld(), request);
            if (plan.mode() == RestorePlanMode.NO_OP || plan.changedBlocks() <= 0) {
                throw new IllegalStateException("Partial restore plan is not actionable for " + label);
            }
            return this.restoreService.partialRestore(server.overworld(), request);
        });
        this.waitForOperation(handle, label);

        this.variantHeadLabels.put(this.activeVariantId, label);

        HistoryJourneyCheckpoint expected = HistoryJourneyCheckpoint.composeSelectedRegion(
                label,
                current,
                this.checkpoints.get(targetLabel),
                selection,
                this.activeVariantId,
                this.variantHeads(),
                this.versionCount
        );
        HistoryJourneyCheckpoint actual = this.capture("actual " + label);
        expected.assertMatches(actual);
        this.checkpoints.put(label, expected);
        this.assertDraftPresent(label);
    }

    private void save(String label, String message) throws Exception {
        HistoryJourneyCheckpoint expectedWorld = this.capture("expected " + label);
        OperationHandle handle = this.singleplayer.getServer().computeOnServer(server ->
                this.versionService.startSaveVersion(server.overworld(), this.projectName, message, ACTOR));
        this.waitForOperation(handle, label);

        VisibleProjectState projectState = this.visibleProjectState();
        String versionId = projectState.activeHeadVersionId();
        this.explicitSaveCount += 1;
        this.versionCount = projectState.versionCount();
        this.variantHeads.put(this.activeVariantId, versionId);
        this.variantHeadLabels.put(this.activeVariantId, label);
        this.versionIds.put(label, versionId);
        this.versionVariants.put(label, this.activeVariantId);

        HistoryJourneyCheckpoint expected = expectedWorld
                .withLabel(label)
                .withProjectState(this.activeVariantId, this.variantHeads(), this.versionCount);
        HistoryJourneyCheckpoint actual = this.capture("actual " + label);
        expected.assertMatches(actual);
        this.checkpoints.put(label, expected);
        this.assertNoDraft(label);
    }

    private VisibleProjectState visibleProjectState() throws Exception {
        return this.singleplayer.getServer().computeOnServer(server -> {
            ProjectVariant activeVariant = this.projectService.loadVariants(server, this.projectName).stream()
                    .filter(variant -> variant.id().equals(this.activeVariantId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Active branch is missing: " + this.activeVariantId));
            return new VisibleProjectState(
                    activeVariant.headVersionId(),
                    this.projectService.loadVersions(server, this.projectName).size()
            );
        });
    }

    private void waitForOperation(OperationHandle handle, String label) throws Exception {
        for (int tick = 0; tick < OPERATION_TIMEOUT_TICKS; tick++) {
            OperationWaitState state = this.singleplayer.getServer().computeOnServer(server -> {
                var snapshot = this.worldOperationManager.snapshot(server, handle).orElse(null);
                boolean active = this.worldOperationManager.hasActiveOperation(server);
                if (snapshot == null) {
                    return new OperationWaitState(false, active, false, "missing", "");
                }
                return new OperationWaitState(
                        snapshot.terminal(),
                        active,
                        snapshot.failed(),
                        snapshot.stage().name(),
                        snapshot.detail()
                );
            });
            if (state.terminal() && !state.active()) {
                if (state.failed()) {
                    throw new AssertionError("Operation failed for " + label + ": " + state.detail());
                }
                this.waitTicks(SETTLE_TICKS);
                return;
            }
            this.context.waitTick();
        }
        throw new AssertionError("Timed out waiting for operation " + label + " handle=" + handle.id());
    }

    private void waitForCheckpointWorld(HistoryJourneyCheckpoint expected, String label) throws Exception {
        HistoryJourneyCheckpoint lastActual = null;
        for (int tick = 0; tick < WORLD_MATCH_TIMEOUT_TICKS; tick++) {
            boolean activeOperation = this.singleplayer.getServer().computeOnServer(server ->
                    this.worldOperationManager.hasActiveOperation(server));
            lastActual = this.capture(label + " wait " + tick)
                    .withProjectState(this.activeVariantId, this.variantHeads(), this.versionCount);
            if (!activeOperation && expected.worldMatches(lastActual)) {
                return;
            }
            this.context.waitTick();
        }
        expected.assertWorldMatches(lastActual);
    }

    private void assertFinalState() throws Exception {
        if (this.explicitSaveCount != 14) {
            throw new AssertionError("Expected 14 explicit saves, actual=" + this.explicitSaveCount);
        }
        int nonMainBranches = this.singleplayer.getServer().computeOnServer(server ->
                (int) this.projectService.loadVariants(server, this.projectName).stream()
                        .filter(variant -> !variant.main())
                        .count());
        if (nonMainBranches != 4) {
            throw new AssertionError("Expected 4 non-main branches, actual=" + nonMainBranches);
        }
        int actualVersionCount = this.singleplayer.getServer().computeOnServer(server ->
                this.projectService.loadVersions(server, this.projectName).size());
        if (actualVersionCount != this.versionCount) {
            throw new AssertionError("Version count model mismatch: expected="
                    + this.versionCount + " actual=" + actualVersionCount);
        }
        boolean valid = this.singleplayer.getServer().computeOnServer(server ->
                this.integrityService.inspect(server, this.projectName).valid());
        if (!valid) {
            throw new AssertionError("History journey project integrity report is invalid");
        }
        this.checkpoints.get("S15-main-final-mixed")
                .assertMatches(this.capture("final actual"));
    }

    private void assertNoDraft(String label) throws Exception {
        boolean draftEmpty = this.singleplayer.getServer().computeOnServer(server ->
                this.recoveryService.loadDraft(server, this.projectName).isEmpty());
        if (!draftEmpty) {
            throw new AssertionError("Recovery draft was not consumed after " + label);
        }
    }

    private void assertDraftPresent(String label) throws Exception {
        boolean draftEmpty = this.singleplayer.getServer().computeOnServer(server ->
                this.recoveryService.loadDraft(server, this.projectName).isEmpty());
        if (draftEmpty) {
            throw new AssertionError("Recovery draft was not retained after " + label);
        }
    }

    private void assertUndoHistoryCleared(String label) {
        if (this.undoRedoHistoryManager.selectUndo(this.projectId) != null
                || this.undoRedoHistoryManager.selectRedo(this.projectId) != null) {
            throw new AssertionError("Runtime undo history survived full restore: " + label);
        }
    }

    private HistoryJourneyCheckpoint capture(String label) throws Exception {
        return this.singleplayer.getServer().computeOnServer(server ->
                HistoryJourneyCheckpoint.capture(
                        label,
                        server.overworld(),
                        this.volume,
                        this.projectService,
                        server,
                        this.projectName
                ));
    }

    private void waitTicks(int ticks) throws Exception {
        for (int tick = 0; tick < ticks; tick++) {
            this.context.waitTick();
        }
    }

    private BlockPos pos(int x, int y, int z) {
        return this.volume.min().offset(x, y, z);
    }

    private Map<String, String> variantHeads() {
        return new LinkedHashMap<>(this.variantHeads);
    }

    private SingleplayerPlayerActionDriver driver(MinecraftServer server) {
        return new SingleplayerPlayerActionDriver(server.overworld(), firstPlayer(server));
    }

    private static ServerPlayer firstPlayer(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            throw new IllegalStateException("No singleplayer test player is available");
        }
        return players.getFirst();
    }

    private void withPlayerSource(Runnable action) {
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushPlayerSource(
                WorldMutationSource.PLAYER,
                ACTOR,
                true
        )) {
            action.run();
        }
    }

    private void assertPlaced(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("Player action failed: " + label);
        }
    }

    private void cleanup() throws Exception {
        if (this.projectName == null || this.volume == null) {
            return;
        }
        this.singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
                for (Entity entity : level.getEntities((Entity) null, this.volume.bounds(),
                        entity -> !(entity instanceof ServerPlayer))) {
                    entity.discard();
                }
                for (BlockPos pos : BlockPos.betweenClosed(this.volume.min(), this.volume.max())) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            });
            if (this.projectId != null && !this.projectId.isBlank()) {
                HistoryCaptureManager.getInstance().discardSession(server, this.projectId);
                this.undoRedoHistoryManager.clearProject(this.projectId);
            }
            this.projectService.setArchived(server, this.projectName, true);
        });
    }

    private record InitialProject(String projectId, String projectName, SingleplayerTestVolume volume) {
    }

    private record VisibleProjectState(String activeHeadVersionId, int versionCount) {
    }

    private record OperationWaitState(
            boolean terminal,
            boolean active,
            boolean failed,
            String stage,
            String detail
    ) {
    }
}
