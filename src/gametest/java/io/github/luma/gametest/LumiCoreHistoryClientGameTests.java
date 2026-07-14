package io.github.luma.gametest;

import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.ui.controller.AsyncCompareCache;
import io.github.luma.ui.controller.BranchCreationResult;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.state.CompareLoadState;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/** End-to-end coverage for the builder save, compare, restore, and branch journey. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiCoreHistoryClientGameTests implements FabricClientGameTest {

    private static final String SAVE_A = "Core journey A";
    private static final String SAVE_B = "Core journey B";
    private static final String SAVE_C = "Core journey branch";
    private static final int MAX_WAIT_TICKS = 1_200;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!ClientGameTestSuiteSelection.includes("core")) {
            return;
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(settings -> settings.setAllowCommands(true))
                .create()) {
            ClientGameTestSingleplayerSupport.prepare(singleplayer);
            AsyncCompareCache.getInstance().clear();
            this.runJourney(context, singleplayer);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Lumi core history client gametest failed", exception);
        } finally {
            AsyncCompareCache.getInstance().clear();
        }
    }

    private void runJourney(ClientGameTestContext context, TestSingleplayerContext singleplayer) throws Exception {
        CoreHistoryWorldFixture fixture = singleplayer.getServer().computeOnServer(server -> {
            server.getPlayerList().getPlayers().getFirst().setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            BlockPos origin = server.getPlayerList().getPlayers().getFirst().blockPosition().offset(4, 1, 4);
            return CoreHistoryWorldFixture.create(server.overworld(), origin);
        });

        UUID entityId = singleplayer.getServer().computeOnServer(server -> fixture.applyStateA(server.overworld()));
        this.waitForDraft(context, singleplayer, fixture.projectId());
        this.startSave(context, fixture, SAVE_A);
        this.waitForOperation(context, singleplayer, fixture.projectId(), "save-version");
        ProjectVersion versionA = this.requireVersion(singleplayer, fixture.projectName(), SAVE_A);
        this.assertNoDraft(singleplayer, fixture);

        singleplayer.getServer().runOnServer(server -> fixture.applyStateB(server.overworld(), entityId));
        this.waitForDraft(context, singleplayer, fixture.projectId());
        this.startSave(context, fixture, SAVE_B);
        this.waitForOperation(context, singleplayer, fixture.projectId(), "save-version");
        ProjectVersion versionB = this.requireVersion(singleplayer, fixture.projectName(), SAVE_B);
        this.assertNoDraft(singleplayer, fixture);
        this.assertCompare(context, fixture.projectName(), versionA.id(), versionB.id());

        this.startRestore(context, fixture, versionA.id());
        this.waitForOperation(context, singleplayer, fixture.projectId(), "restore-version");
        singleplayer.getServer().runOnServer(server -> fixture.assertStateA(server.overworld(), entityId));
        this.assertNoDraft(singleplayer, fixture);

        this.startRestore(context, fixture, versionB.id());
        this.waitForOperation(context, singleplayer, fixture.projectId(), "restore-version");
        singleplayer.getServer().runOnServer(server -> fixture.assertStateB(server.overworld(), entityId));

        BranchCreationResult branch = context.computeOnClient(client ->
                new ProjectScreenController().createAndSwitchVariant(
                        fixture.projectName(), "Core experiment", versionB.id()));
        if (!branch.switched()) {
            throw new AssertionError("Branch creation failed: " + branch.statusKey());
        }
        this.waitForOperation(context, singleplayer, fixture.projectId(), "restore-version");
        singleplayer.getServer().runOnServer(server -> fixture.applyStateC(server.overworld()));
        this.waitForDraft(context, singleplayer, fixture.projectId());
        this.startSave(context, fixture, SAVE_C);
        this.waitForOperation(context, singleplayer, fixture.projectId(), "save-version");
        this.requireVersion(singleplayer, fixture.projectName(), SAVE_C);

        this.switchBranch(context, fixture, "main");
        this.waitForOperation(context, singleplayer, fixture.projectId(), "restore-version");
        singleplayer.getServer().runOnServer(server -> fixture.assertStateB(server.overworld(), entityId));
        this.switchBranch(context, fixture, branch.variantId());
        this.waitForOperation(context, singleplayer, fixture.projectId(), "restore-version");
        singleplayer.getServer().runOnServer(server -> fixture.assertStateC(server.overworld(), entityId));
        this.assertNoDraft(singleplayer, fixture);
    }

    private void startSave(ClientGameTestContext context, CoreHistoryWorldFixture fixture, String message) throws Exception {
        String status = context.computeOnClient(client ->
                new ProjectScreenController().saveVersion(fixture.projectName(), message));
        this.assertStatus("luma.status.save_started", status, "save");
    }

    private void startRestore(ClientGameTestContext context, CoreHistoryWorldFixture fixture, String versionId) throws Exception {
        String status = context.computeOnClient(client ->
                new ProjectScreenController().restoreVersion(fixture.projectName(), versionId));
        this.assertStatus("luma.status.restore_started", status, "restore");
    }

    private void switchBranch(ClientGameTestContext context, CoreHistoryWorldFixture fixture, String branchId) throws Exception {
        String status = context.computeOnClient(client ->
                new ProjectScreenController().switchVariant(fixture.projectName(), branchId));
        this.assertStatus("luma.status.variant_switched", status, "branch switch");
    }

    private void assertCompare(ClientGameTestContext context, String projectName, String left, String right)
            throws Exception {
        for (int tick = 0; tick < MAX_WAIT_TICKS; tick++) {
            var state = context.computeOnClient(client ->
                    new CompareScreenController().loadState(projectName, left, right, ""));
            if (state.loadState() == CompareLoadState.FAILED) {
                throw new AssertionError("Core journey compare failed");
            }
            if (state.loadState() == CompareLoadState.READY) {
                if (state.diff() == null || state.diff().changedBlockCount() != 3
                        || state.diff().changedEntityCount() != 1) {
                    throw new AssertionError("Unexpected core journey diff: " + state.diff());
                }
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("Core journey compare timed out");
    }

    private void waitForDraft(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            String projectId
    ) throws Exception {
        for (int tick = 0; tick < MAX_WAIT_TICKS; tick++) {
            boolean ready = singleplayer.getServer().computeOnServer(server ->
                    HistoryCaptureManager.getInstance().snapshotDraft(server, projectId)
                            .map(draft -> !draft.isEmpty())
                            .orElse(false));
            if (ready) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("Core journey draft timed out");
    }

    private void waitForOperation(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            String projectId,
        String label
    ) throws Exception {
        for (int tick = 0; tick < MAX_WAIT_TICKS; tick++) {
            OperationState state = singleplayer.getServer().computeOnServer(server -> new OperationState(
                    WorldOperationManager.getInstance().snapshot(server, projectId).orElse(null),
                    WorldOperationManager.getInstance().hasActiveOperation(server)
            ));
            OperationSnapshot snapshot = state.snapshot();
            if (snapshot != null && label.equals(snapshot.handle().label()) && snapshot.terminal()) {
                if (snapshot.failed()) {
                    throw new AssertionError(label + " failed: " + snapshot.detail());
                }
                if (!state.active()) {
                    return;
                }
            }
            context.waitTick();
        }
        throw new AssertionError(label + " timed out");
    }

    private ProjectVersion requireVersion(
            TestSingleplayerContext singleplayer,
            String projectName,
            String message
    ) throws Exception {
        return singleplayer.getServer().computeOnServer(server -> new ProjectService()
                .loadVersions(server, projectName).stream()
                .filter(version -> message.equals(version.message()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing saved version " + message)));
    }

    private void assertNoDraft(TestSingleplayerContext singleplayer, CoreHistoryWorldFixture fixture) throws Exception {
        boolean present = singleplayer.getServer().computeOnServer(server ->
                new RecoveryService().loadDraft(server, fixture.projectName()).isPresent());
        if (present) {
            throw new AssertionError("Core journey left a recovery draft");
        }
    }

    private void assertStatus(String expected, String actual, String action) {
        if (!expected.equals(actual)) {
            throw new AssertionError(action + " returned " + actual);
        }
    }

    private record OperationState(OperationSnapshot snapshot, boolean active) {
    }

}
