package io.github.luma.gametest;

import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.integration.common.ExternalToolIntegrationRegistry;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.ui.controller.ProjectScreenController;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;

/** End-to-end history coverage through the real WorldEdit and Axiom runtimes. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiBuilderToolsClientGameTests implements FabricClientGameTest {

    private static final String WORLDEDIT_SAVE = "Builder tools: WorldEdit";
    private static final String AXIOM_SAVE = "Builder tools: Axiom";
    private static final String AXIOM_INTRO_SCREEN = "com.moulberry.axiom.screen.AxiomIntroductionScreen";
    private static final int MAX_WAIT_TICKS = 1_200;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!ClientGameTestSuiteSelection.includes("tools")) {
            return;
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(settings -> settings.setAllowCommands(true))
                .create()) {
            this.dismissAxiomIntroduction(context);
            ClientGameTestSingleplayerSupport.prepare(singleplayer);
            this.runJourney(context, singleplayer);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Lumi builder-tools client gametest failed", exception);
        }
    }

    private void dismissAxiomIntroduction(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.screen != null && AXIOM_INTRO_SCREEN.equals(client.screen.getClass().getName())) {
                client.setScreen(null);
            }
        });
    }

    private void runJourney(ClientGameTestContext context, TestSingleplayerContext singleplayer) throws Exception {
        BuilderToolWorldFixture fixture = singleplayer.getServer().computeOnServer(server -> {
            this.assertIntegrationsAvailable();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.CREATIVE);
            BlockPos origin = player.blockPosition().offset(4, 1, 4);
            return BuilderToolWorldFixture.create(server.overworld(), origin);
        });

        singleplayer.getServer().runOnServer(server -> fixture.applyWorldEditState(server.overworld()));
        this.waitForDraft(context, singleplayer, fixture, WorldMutationSource.WORLDEDIT);
        this.startSave(context, fixture, WORLDEDIT_SAVE);
        this.waitForOperation(context, singleplayer, fixture.projectId(), "save-version");
        ProjectVersion worldEditVersion = this.requireVersion(singleplayer, fixture.projectName(), WORLDEDIT_SAVE);

        singleplayer.getServer().runOnServer(server -> fixture.applyAxiomState(
                server.overworld(),
                server.getPlayerList().getPlayers().getFirst()
        ));
        this.waitForDraft(context, singleplayer, fixture, WorldMutationSource.AXIOM);
        this.startSave(context, fixture, AXIOM_SAVE);
        this.waitForOperation(context, singleplayer, fixture.projectId(), "save-version");
        ProjectVersion axiomVersion = this.requireVersion(singleplayer, fixture.projectName(), AXIOM_SAVE);

        this.startRestore(context, fixture, worldEditVersion.id());
        this.waitForOperation(context, singleplayer, fixture.projectId(), "restore-version");
        singleplayer.getServer().runOnServer(server -> fixture.assertWorldEditState(server.overworld()));

        this.startRestore(context, fixture, axiomVersion.id());
        this.waitForOperation(context, singleplayer, fixture.projectId(), "restore-version");
        singleplayer.getServer().runOnServer(server -> fixture.assertAxiomState(server.overworld()));
    }

    private void assertIntegrationsAvailable() {
        ExternalToolIntegrationRegistry integrations = new ExternalToolIntegrationRegistry();
        if (!integrations.worldEditStatus().available()) {
            throw new AssertionError("WorldEdit runtime is not available in builder-tool gate");
        }
        if (!integrations.axiomStatus().available()) {
            throw new AssertionError("Axiom runtime is not available in builder-tool gate");
        }
    }

    private void waitForDraft(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            BuilderToolWorldFixture fixture,
            WorldMutationSource source
    ) throws Exception {
        for (int tick = 0; tick < MAX_WAIT_TICKS; tick++) {
            boolean present = singleplayer.getServer().computeOnServer(server ->
                    HistoryCaptureManager.getInstance()
                            .snapshotDraft(server, fixture.projectId())
                            .map(draft -> !draft.isEmpty())
                            .orElse(false));
            if (present) {
                singleplayer.getServer().runOnServer(server ->
                        fixture.assertDraftSource(server.overworld(), source));
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(source + " draft timed out");
    }

    private void startSave(
            ClientGameTestContext context,
            BuilderToolWorldFixture fixture,
            String message
    ) throws Exception {
        String status = context.computeOnClient(client ->
                new ProjectScreenController().saveVersion(fixture.projectName(), message));
        this.assertStatus("luma.status.save_started", status, "save");
    }

    private void startRestore(
            ClientGameTestContext context,
            BuilderToolWorldFixture fixture,
            String versionId
    ) throws Exception {
        String status = context.computeOnClient(client ->
                new ProjectScreenController().restoreVersion(fixture.projectName(), versionId));
        this.assertStatus("luma.status.restore_started", status, "restore");
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

    private void assertStatus(String expected, String actual, String action) {
        if (!expected.equals(actual)) {
            throw new AssertionError(action + " returned " + actual);
        }
    }

    private record OperationState(OperationSnapshot snapshot, boolean active) {
    }
}
