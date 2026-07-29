package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.minecraft.world.DimensionFreeze;
import io.github.lumi.minecraft.world.PersistedEntityUuidOracle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;

/** Measures real branch switches in an isolated copy of an existing world. */
final class LumiExistingWorldBranchSwitchScenario {
    private static final int HISTORY_LIMIT = 1_000;
    private final LumiBehaviorReport report;
    private final LumiBehaviorOperations operations;
    private final TestServerContext server;
    private final LumiUiTestDriver ui;

    LumiExistingWorldBranchSwitchScenario(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            LumiBehaviorReport report) {
        this.report = report;
        server = singleplayer.getServer();
        operations = new LumiBehaviorOperations(
                context, server, report);
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
        auditPersistedEntityUuids();

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

    private void auditPersistedEntityUuids() throws IOException {
        long started = System.nanoTime();
        AuditContext audit = server.computeOnServer(AuditContext::open);
        Throwable failure = null;
        try {
            PersistedEntityUuidOracle.Result result = audit.oracle.audit();
            report.event("gate", "persisted_entity_uuid_uniqueness", "succeeded",
                    0, elapsedMillis(started), "chunks=" + result.chunks()
                            + ";entities=" + result.entities());
        } catch (IOException | RuntimeException | Error failed) {
            failure = failed;
            report.event("gate", "persisted_entity_uuid_uniqueness", "failed",
                    0, elapsedMillis(started), failed.toString());
            throw failed;
        } finally {
            try {
                server.runOnServer(ignored -> audit.release());
            } catch (RuntimeException releaseFailed) {
                if (failure == null) {
                    throw releaseFailed;
                }
                failure.addSuppressed(releaseFailed);
            }
        }
    }

    private void assertEndpoint(String name, BranchRef endpoint)
            throws IOException {
        if (!operations.activeBranch().equals(endpoint.name())
                || !operations.activeCommit().equals(endpoint.commit())) {
            throw new AssertionError(name + " did not publish the expected endpoint");
        }
    }

    record Fixture(BranchRef initial, BranchRef latest) { }

    private static final class AuditContext {
        private final PersistedEntityUuidOracle oracle;
        private final List<DimensionFreeze.Lease> leases;

        private AuditContext(
                PersistedEntityUuidOracle oracle,
                List<DimensionFreeze.Lease> leases) {
            this.oracle = oracle;
            this.leases = leases;
        }

        private static AuditContext open(MinecraftServer server) {
            List<DimensionFreeze.Lease> leases = new ArrayList<>();
            try {
                for (var level : server.getAllLevels()) {
                    leases.add(LumiMod.serverRuntime().find(level).orElseThrow(
                            () -> new IllegalStateException(
                                    "Missing Lumi runtime for "
                                            + level.dimension().identifier()))
                            .freeze().acquire());
                }
                return new AuditContext(
                        PersistedEntityUuidOracle.open(server),
                        List.copyOf(leases));
            } catch (RuntimeException | Error failed) {
                release(leases, failed);
                throw failed;
            }
        }

        private void release() {
            RuntimeException failure = release(leases, null);
            if (failure != null) {
                throw failure;
            }
        }

        private static RuntimeException release(
                List<DimensionFreeze.Lease> leases, Throwable parent) {
            RuntimeException first = null;
            for (int index = leases.size() - 1; index >= 0; index--) {
                try {
                    leases.get(index).release();
                } catch (RuntimeException failed) {
                    if (parent != null) {
                        parent.addSuppressed(failed);
                    } else if (first == null) {
                        first = failed;
                    } else {
                        first.addSuppressed(failed);
                    }
                }
            }
            return first;
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
