package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import java.io.IOException;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Verifies exact Restore endpoints without using the performance timeline. */
final class LumiExactRestoreScenario {
    private static final int IDLE_TIMEOUT_TICKS = 400;
    private final ClientGameTestContext context;
    private final TestSingleplayerContext singleplayer;
    private final LumiBehaviorReport report;
    private final LumiBehaviorChecks checks;
    private final LumiBehaviorOperations operations;
    private final LumiExactRestoreFixture fixture;

    LumiExactRestoreScenario(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            LumiBehaviorReport report) {
        this.context = context;
        this.singleplayer = singleplayer;
        this.report = report;
        checks = new LumiBehaviorChecks(context, singleplayer, report);
        operations = new LumiBehaviorOperations(
                context, singleplayer.getServer(), report);
        fixture = new LumiExactRestoreFixture(context, singleplayer, report);
    }

    PreparedExact run(LumiUiTestDriver ui) throws IOException {
        ui.completeOnboardingIfShown();
        ui.awaitHistory();

        Endpoint a = createEndpoint("a", fixture::buildA);
        Endpoint b = createEndpoint("b", fixture::buildB);
        Endpoint c = createEndpoint("c", fixture::buildC);
        Endpoint d = createEndpoint("d", fixture::buildD);
        BranchName workingBranch = operations.activeBranch();
        operations.createBranch("exact-a", a.commit());
        BranchRef branchB = operations.createBranch("exact-b", b.commit());
        operations.createBranch("exact-c", c.commit());
        BranchRef branchD = operations.createBranch("exact-d", d.commit());

        measureRestoreAndAssertPath(
                "loaded_d_to_a", a, workingBranch, true, false);
        restoreAndAssert("loaded_a_to_b", b, workingBranch);
        restoreAndAssert("loaded_b_to_a", a, workingBranch);
        restoreAndAssert("loaded_a_to_d", d, workingBranch);

        switchAndAssert("branch_d_to_b", branchB.name(), b);
        switchAndAssert("branch_b_to_d", branchD.name(), d);

        checks.finish();
        report.event("gate", "exact_restore", "succeeded", 0, 0,
                "endpoints=4;blocks=262144;loaded=true;branchSwitch=true");
        return new PreparedExact(b, c, d, branchD.name());
    }

    void verifyReopened(PreparedExact prepared) throws IOException {
        Objects.requireNonNull(prepared, "prepared");
        LumiUiTestDriver ui = new LumiUiTestDriver(context);
        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        assertEndpointMetadata(
                "reopened_d", prepared.endpointD(), prepared.branch());
        LumiRestoreMeasurement mixed = operations.measureRestore(
                "reopened_mixed_d_to_c", prepared.endpointC().commit());
        assertChunkPath("reopened_mixed_d_to_c", mixed, true, true);
        assertEndpointMetadata(
                "reopened_mixed_d_to_c",
                prepared.endpointC(), prepared.branch());
        LumiRestoreMeasurement mixedReverse = operations.measureRestore(
                "reopened_mixed_c_to_d", prepared.endpointD().commit());
        assertChunkPath("reopened_mixed_c_to_d", mixedReverse, true, true);
        assertEndpointMetadata(
                "reopened_mixed_c_to_d",
                prepared.endpointD(), prepared.branch());
        LumiRestoreMeasurement loaded = operations.measureRestore(
                "reopened_loaded_d_to_b", prepared.endpointB().commit());
        assertChunkPath("reopened_loaded_d_to_b", loaded, true, false);
        assertEndpointMetadata(
                "reopened_loaded_d_to_b",
                prepared.endpointB(), prepared.branch());
        LumiRestoreMeasurement stored = operations.measureRestore(
                "reopened_stored_b_to_c", prepared.endpointC().commit());
        assertChunkPath("reopened_stored_b_to_c", stored, false, true);
        assertEndpointMetadata(
                "reopened_stored_b_to_c",
                prepared.endpointC(), prepared.branch());
        LumiRestoreMeasurement storedReverse = operations.measureRestore(
                "reopened_stored_c_to_b", prepared.endpointB().commit());
        assertChunkPath(
                "reopened_stored_c_to_b", storedReverse, false, true);
        assertEndpoint(
                "reopened_stored_c_to_b",
                prepared.endpointB(), prepared.branch());
        checks.finish();
        report.event("gate", "exact_restore_reopen", "succeeded", 0, 0,
                "mixed=true;stored=true;exact=true");
    }

    private Endpoint createEndpoint(String name, Runnable mutation)
            throws IOException {
        mutation.run();
        checks.awaitQuiescence("exact_" + name, fixture.areas());
        operations.awaitDurability("exact_" + name);
        LumiBehaviorOperations.SavedBoundary saved =
                operations.save("exact-" + name, fixture.areas());
        Endpoint endpoint = new Endpoint(
                saved.commit(), saved.snapshot(), fixture.derivedState());
        assertRuntimeReleased("endpoint_" + name);
        return endpoint;
    }

    private void restoreAndAssert(
            String name, Endpoint expected, BranchName expectedBranch)
            throws IOException {
        operations.restore(name, expected.commit());
        assertEndpoint(name, expected, expectedBranch);
    }

    private void measureRestoreAndAssertPath(
            String name,
            Endpoint expected,
            BranchName expectedBranch,
            boolean loaded,
            boolean stored) throws IOException {
        LumiRestoreMeasurement measurement =
                operations.measureRestore(name, expected.commit());
        assertChunkPath(name, measurement, loaded, stored);
        assertEndpoint(name, expected, expectedBranch);
    }

    private void assertChunkPath(
            String name,
            LumiRestoreMeasurement measurement,
            boolean loaded,
            boolean stored) {
        checks.assertValue(name + "_loaded_path",
                Boolean.toString(measurement.apply().loadedChunks() > 0),
                Boolean.toString(loaded));
        checks.assertValue(name + "_stored_path",
                Boolean.toString(measurement.apply().storedChunks() > 0),
                Boolean.toString(stored));
        recordChunkPath(name, measurement);
    }

    private void recordChunkPath(
            String name, LumiRestoreMeasurement measurement) {
        report.event("path", name, "verified", 0, 0,
                "loadedChunks=" + measurement.apply().loadedChunks()
                        + ";storedChunks=" + measurement.apply().storedChunks()
                        + ";fallbacks=" + measurement.apply().storedFallbacks());
    }

    private void switchAndAssert(
            String name, BranchName target, Endpoint expected)
            throws IOException {
        operations.switchBranch(name, target);
        assertEndpoint(name, expected, target);
    }

    private void assertEndpoint(
            String name, Endpoint expected, BranchName expectedBranch)
            throws IOException {
        checks.assertSnapshot(
                name + "_exact", fixture.areas(), expected.snapshot());
        assertEndpointMetadata(name, expected, expectedBranch);
    }

    private void assertEndpointMetadata(
            String name, Endpoint expected, BranchName expectedBranch)
            throws IOException {
        checks.assertValue(
                name + "_derived",
                fixture.derivedState().toString(),
                expected.derived().toString());
        checks.assertValue(
                name + "_branch",
                operations.activeBranch().value(),
                expectedBranch.value());
        checks.assertValue(
                name + "_head",
                operations.activeCommit().hex(),
                expected.commit().hex());
        assertRuntimeReleased(name);
    }

    private void assertRuntimeReleased(String name) {
        checks.waitUntil(name + "_runtime_idle", IDLE_TIMEOUT_TICKS,
                () -> singleplayer.getServer().computeOnServer(server -> {
                    var runtime = runtime(server);
                    return !runtime.operations().hasActiveOperation()
                            && runtime.operations().queuedCount() == 0
                            && !runtime.freeze().isFrozen();
                }));
        RuntimeState state = singleplayer.getServer().computeOnServer(server -> {
            var runtime = runtime(server);
            return new RuntimeState(
                    runtime.recoveryJournal().isPresent(),
                    runtime.operations().hasActiveOperation(),
                    runtime.operations().queuedCount(),
                    runtime.freeze().isFrozen());
        });
        checks.assertValue(name + "_journal",
                Boolean.toString(state.journal()), "false");
        checks.assertValue(name + "_active_operation",
                Boolean.toString(state.active()), "false");
        checks.assertValue(name + "_queued_operations",
                Integer.toString(state.queued()), "0");
        checks.assertValue(name + "_frozen",
                Boolean.toString(state.frozen()), "false");
    }

    private static io.github.lumi.minecraft.runtime.FabricDimensionRuntime runtime(
            net.minecraft.server.MinecraftServer server) {
        var level = server.getPlayerList().getPlayers().getFirst().level();
        return LumiMod.serverRuntime().find(level).orElseThrow();
    }

    record PreparedExact(
            Endpoint endpointB,
            Endpoint endpointC,
            Endpoint endpointD,
            BranchName branch) {
        PreparedExact {
            Objects.requireNonNull(endpointB, "endpointB");
            Objects.requireNonNull(endpointC, "endpointC");
            Objects.requireNonNull(endpointD, "endpointD");
            Objects.requireNonNull(branch, "branch");
        }
    }

    record Endpoint(
            io.github.lumi.domain.model.CommitId commit,
            LumiWorldSnapshot snapshot,
            LumiExactRestoreFixture.DerivedState derived) {
        Endpoint {
            Objects.requireNonNull(commit, "commit");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(derived, "derived");
        }
    }

    private record RuntimeState(
            boolean journal, boolean active, int queued, boolean frozen) { }
}
