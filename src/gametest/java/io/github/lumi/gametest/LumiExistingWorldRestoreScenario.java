package io.github.lumi.gametest;

import io.github.lumi.domain.model.CommitId;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Measures real UI restores against an isolated copy of an existing world. */
final class LumiExistingWorldRestoreScenario {
    private static final int HISTORY_LIMIT = 1_000;
    private final LumiBehaviorReport report;
    private final LumiBehaviorOperations operations;
    private final LumiUiTestDriver ui;

    LumiExistingWorldRestoreScenario(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            LumiBehaviorReport report) {
        this.report = report;
        operations = new LumiBehaviorOperations(
                context, singleplayer.getServer(), report);
        ui = new LumiUiTestDriver(context);
    }

    void run() throws IOException {
        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        List<CommitId> history = operations.history(HISTORY_LIMIT);
        int samples = Integer.getInteger(
                LumiHistoryBenchmarkConfig.PREFIX + "restoreSamples", 3);
        List<CommitId> targets = restoreTargets(history, samples);
        report.event("benchmark", "existing_world", "started", 0, 0,
                "versions=" + history.size() + ";targets="
                        + targets.stream().map(CommitId::hex).toList());
        int number = 0;
        for (CommitId target : targets) {
            if (!operations.history(HISTORY_LIMIT).contains(target)) {
                throw new IllegalArgumentException(
                        "Restore target is no longer builder-visible: "
                                + target.hex());
            }
            String name = String.format(
                    "existing-%02d-%s", ++number,
                    target.hex().substring(0, 12));
            LumiRestoreMeasurement measurement =
                    operations.measureRestore(name, target);
            report.event("restore_metrics", name, "measured", 0,
                    measurement.totalMillis(), measurement.describe());
        }
        report.event("benchmark", "existing_world", "succeeded", 0, 0,
                "versions=" + history.size() + ";restores=" + targets.size());
    }

    private static List<CommitId> restoreTargets(
            List<CommitId> history, int samples) {
        return LumiHistoryBenchmarkConfig.restoreTargets().orElseGet(() ->
                restoreIndices(history.size(), samples).stream()
                        .map(history::get).toList());
    }

    static List<Integer> restoreIndices(int versionCount, int samples) {
        if (versionCount < 2) {
            throw new IllegalArgumentException(
                    "Existing world needs at least two visible versions");
        }
        if (samples < 1) {
            throw new IllegalArgumentException(
                    "restoreSamples must be positive");
        }
        int last = versionCount - 1;
        int count = Math.min(samples, last);
        LinkedHashSet<Integer> indices = new LinkedHashSet<>();
        for (int sample = 1; sample <= count; sample++) {
            indices.add(Math.max(1,
                    (int) Math.round(last * sample / (double) count)));
        }
        return List.copyOf(indices);
    }
}
