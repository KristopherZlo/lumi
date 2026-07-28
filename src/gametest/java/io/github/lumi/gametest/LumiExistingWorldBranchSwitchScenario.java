package io.github.lumi.gametest;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import java.io.IOException;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Measures real branch switches in an isolated copy of an existing world. */
final class LumiExistingWorldBranchSwitchScenario {
    private static final int HISTORY_LIMIT = 1_000;
    private final LumiBehaviorReport report;
    private final LumiBehaviorOperations operations;
    private final LumiUiTestDriver ui;

    LumiExistingWorldBranchSwitchScenario(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            LumiBehaviorReport report) {
        this.report = report;
        operations = new LumiBehaviorOperations(
                context, singleplayer.getServer(), report);
        ui = new LumiUiTestDriver(context);
    }

    Fixture prepare() throws IOException {
        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        List<CommitId> endpoints = LumiHistoryBenchmarkConfig.restoreTargets()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Existing-world branch switch requires restoreTargets"));
        if (endpoints.size() != 2 || endpoints.get(0).equals(endpoints.get(1))) {
            throw new IllegalArgumentException(
                    "Existing-world branch switch requires two distinct restoreTargets");
        }
        List<CommitId> history = operations.history(HISTORY_LIMIT);
        if (!history.containsAll(endpoints)) {
            throw new IllegalArgumentException(
                    "Both branch-switch endpoints must be builder-visible");
        }

        BranchRef initial = operations.createBranch(
                "benchmark-existing-initial", endpoints.get(0));
        BranchRef latest = operations.createBranch(
                "benchmark-existing-latest", endpoints.get(1));
        ui.completeOnboardingIfShown();
        operations.switchBranch("existing_setup_latest", latest.name());
        assertEndpoint("existing_setup_latest", latest);
        report.event("benchmark", "existing_world_branch_switch", "started",
                0, 0, "initial=" + initial.commit().hex()
                        + ";latest=" + latest.commit().hex());
        return new Fixture(initial, latest);
    }

    void run(Fixture fixture) throws IOException {
        if (fixture == null) {
            throw new IllegalStateException("Existing-world fixture is absent");
        }
        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        assertEndpoint("reopened_latest", fixture.latest());

        measure("cold-latest-to-initial", fixture.initial());
        operations.switchBranch(
                "prime-initial-to-latest", fixture.latest().name());
        assertEndpoint("prime_initial_to_latest", fixture.latest());
        measure("warm-latest-to-initial", fixture.initial());
        measure("warm-initial-to-latest", fixture.latest());

        report.event("benchmark", "existing_world_branch_switch", "succeeded",
                0, 0, "restores=3");
    }

    private void measure(String name, BranchRef endpoint) throws IOException {
        LumiRestoreMeasurement measurement =
                operations.measureBranchSwitch(name, endpoint.name());
        report.event("restore_metrics", name, "measured", 0,
                measurement.operationMillis(), measurement.describe());
        assertEndpoint(name, endpoint);
    }

    private void assertEndpoint(String name, BranchRef endpoint)
            throws IOException {
        if (!operations.activeBranch().equals(endpoint.name())
                || !operations.activeCommit().equals(endpoint.commit())) {
            throw new AssertionError(name + " did not publish the expected endpoint");
        }
    }

    record Fixture(BranchRef initial, BranchRef latest) { }
}
