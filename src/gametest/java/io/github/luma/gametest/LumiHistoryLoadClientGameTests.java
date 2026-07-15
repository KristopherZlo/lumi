package io.github.luma.gametest;

import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.ui.controller.AsyncCompareCache;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.state.CompareLoadState;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Blocking exactness, duration, progress, and tick-work gate for 50k/100k histories. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiHistoryLoadClientGameTests implements FabricClientGameTest {

    private static final int MUTATION_BATCH = 24;
    private static final int VERIFY_BATCH = 1_024;
    private static final int MAX_WAIT_TICKS = 6_000;
    private static final long MAX_OPERATION_MILLIS = 120_000L;
    private static final long MAX_BATCH_MILLIS = 50L;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!ClientGameTestSuiteSelection.includes("load")) {
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
            throw new RuntimeException("Lumi history load client gametest failed", exception);
        } finally {
            AsyncCompareCache.getInstance().clear();
        }
    }

    private void runJourney(ClientGameTestContext context, TestSingleplayerContext singleplayer) throws Exception {
        HistoryLoadWorldFixture fixture = singleplayer.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            return HistoryLoadWorldFixture.create(server.overworld(), player.blockPosition());
        });

        long maxMutationBatch = this.mutate(context, singleplayer, fixture, Blocks.STONE,
                HistoryLoadWorldFixture.TOTAL_CHANGES);
        ProjectVersion versionA = this.save(context, singleplayer, fixture, "Load gate A", "save-a");

        maxMutationBatch = Math.max(maxMutationBatch, this.mutate(context, singleplayer, fixture, Blocks.GOLD_BLOCK,
                HistoryLoadWorldFixture.HALF_CHANGES));
        ProjectVersion versionB = this.save(context, singleplayer, fixture, "Load gate B", "save-b");
        this.compare(context, fixture, versionA.id(), versionB.id(), HistoryLoadWorldFixture.HALF_CHANGES, "compare-50k");

        maxMutationBatch = Math.max(maxMutationBatch, this.mutate(context, singleplayer, fixture, Blocks.DIAMOND_BLOCK,
                HistoryLoadWorldFixture.TOTAL_CHANGES));
        ProjectVersion versionC = this.save(context, singleplayer, fixture, "Load gate C", "save-c");
        this.compare(context, fixture, versionB.id(), versionC.id(), HistoryLoadWorldFixture.TOTAL_CHANGES, "compare-100k");

        long maxVerifyBatch = this.restoreAndVerify(context, singleplayer, fixture, versionA.id(), 0, "restore-a");
        maxVerifyBatch = Math.max(maxVerifyBatch,
                this.restoreAndVerify(context, singleplayer, fixture, versionB.id(), 1, "restore-b"));
        maxVerifyBatch = Math.max(maxVerifyBatch,
                this.restoreAndVerify(context, singleplayer, fixture, versionC.id(), 2, "restore-c"));
        this.assertNoDraft(singleplayer, fixture);
        LumaLoadLog.event("history-load", "complete",
                "changes50k=" + HistoryLoadWorldFixture.HALF_CHANGES
                        + ", changes100k=" + HistoryLoadWorldFixture.TOTAL_CHANGES
                        + ", maxMutationBatchMs=" + maxMutationBatch
                        + ", maxVerifyBatchMs=" + maxVerifyBatch
                        + ", exactStates=3");
    }

    private long mutate(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            HistoryLoadWorldFixture fixture,
            Block target,
            int changes
    ) throws Exception {
        long coldStartMillis = singleplayer.getServer().computeOnServer(server -> {
            long startedAt = System.nanoTime();
            fixture.applyBatch(server.overworld(), target, 0, 1);
            return (System.nanoTime() - startedAt) / 1_000_000L;
        });
        LumaLoadLog.event("history-load", "capture-cold-start", "durationMs=" + coldStartMillis);
        context.waitTick();

        long maxMillis = 0L;
        for (int start = 1; start < changes; start += MUTATION_BATCH) {
            int batchStart = start;
            int batchSize = Math.min(MUTATION_BATCH, changes - batchStart);
            long elapsed = singleplayer.getServer().computeOnServer(server -> {
                long startedAt = System.nanoTime();
                fixture.applyBatch(server.overworld(), target, batchStart, batchSize);
                return (System.nanoTime() - startedAt) / 1_000_000L;
            });
            maxMillis = Math.max(maxMillis, elapsed);
            this.requireBatchBudget("mutation", elapsed);
            context.waitTick();
        }
        return maxMillis;
    }

    private ProjectVersion save(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            HistoryLoadWorldFixture fixture,
            String message,
            String sample
    ) throws Exception {
        String status = context.computeOnClient(client ->
                new ProjectScreenController().saveVersion(fixture.projectName(), message));
        this.requireStatus("luma.status.save_started", status, sample);
        long elapsed = this.waitForOperation(context, singleplayer, fixture.projectId(), "save-version");
        this.recordDuration(sample, elapsed);
        return singleplayer.getServer().computeOnServer(server -> new ProjectService()
                .loadVersions(server, fixture.projectName()).stream()
                .filter(version -> message.equals(version.message()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + message)));
    }

    private void compare(
            ClientGameTestContext context,
            HistoryLoadWorldFixture fixture,
            String left,
            String right,
            int expectedChanges,
            String sample
    ) throws Exception {
        AsyncCompareCache.getInstance().clear();
        long startedAt = System.nanoTime();
        for (int tick = 0; tick < MAX_WAIT_TICKS; tick++) {
            var state = context.computeOnClient(client ->
                    new CompareScreenController().loadState(fixture.projectName(), left, right, ""));
            if (state.loadState() == CompareLoadState.FAILED) {
                throw new AssertionError(sample + " failed");
            }
            if (state.loadState() == CompareLoadState.READY) {
                int actual = state.diff() == null ? -1 : state.diff().changedBlockCount();
                if (actual != expectedChanges || state.diff().changedEntityCount() != 0) {
                    throw new AssertionError(sample + " expected " + expectedChanges + " changes, found " + actual);
                }
                this.recordDuration(sample, (System.nanoTime() - startedAt) / 1_000_000L);
                AsyncCompareCache.getInstance().clear();
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(sample + " timed out");
    }

    private long restoreAndVerify(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            HistoryLoadWorldFixture fixture,
            String versionId,
            int state,
            String sample
    ) throws Exception {
        String status = context.computeOnClient(client ->
                new ProjectScreenController().restoreVersion(fixture.projectName(), versionId));
        this.requireStatus("luma.status.restore_started", status, sample);
        this.recordDuration(sample, this.waitForOperation(context, singleplayer, fixture.projectId(), "restore-version"));

        long maxMillis = 0L;
        for (int start = 0; start < HistoryLoadWorldFixture.TOTAL_CHANGES; start += VERIFY_BATCH) {
            int batchStart = start;
            long elapsed = singleplayer.getServer().computeOnServer(server -> {
                long startedAt = System.nanoTime();
                fixture.assertBatch(server.overworld(), state, batchStart, VERIFY_BATCH);
                return (System.nanoTime() - startedAt) / 1_000_000L;
            });
            maxMillis = Math.max(maxMillis, elapsed);
            this.requireBatchBudget("verification", elapsed);
            context.waitTick();
        }
        return maxMillis;
    }

    private long waitForOperation(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            String projectId,
            String label
    ) throws Exception {
        long startedAt = System.nanoTime();
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
                    return (System.nanoTime() - startedAt) / 1_000_000L;
                }
            }
            context.waitTick();
        }
        throw new AssertionError(label + " timed out");
    }

    private void assertNoDraft(TestSingleplayerContext singleplayer, HistoryLoadWorldFixture fixture) throws Exception {
        boolean present = singleplayer.getServer().computeOnServer(server ->
                new RecoveryService().loadDraft(server, fixture.projectName()).isPresent());
        if (present) {
            throw new AssertionError("History load journey left a recovery draft");
        }
    }

    private void recordDuration(String sample, long millis) {
        if (millis > MAX_OPERATION_MILLIS) {
            throw new AssertionError(sample + " took " + millis + " ms");
        }
        LumaLoadLog.event("history-load", sample, "durationMs=" + millis);
    }

    private void requireBatchBudget(String kind, long millis) {
        if (millis > MAX_BATCH_MILLIS) {
            throw new AssertionError(kind + " batch took " + millis + " ms");
        }
    }

    private void requireStatus(String expected, String actual, String action) {
        if (!expected.equals(actual)) {
            throw new AssertionError(action + " returned " + actual);
        }
    }

    private record OperationState(OperationSnapshot snapshot, boolean active) {
    }
}
