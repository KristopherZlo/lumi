package io.github.lumi.gametest;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/** Creates dense random history and measures real UI Save and Restore workflows. */
final class LumiHistoryBenchmarkScenario {
    private final LumiHistoryBenchmarkConfig config;
    private final LumiBehaviorReport report;
    private final LumiBehaviorActions actions;
    private final LumiBehaviorOperations operations;
    private final LumiRepositoryMetrics metrics = new LumiRepositoryMetrics();
    private final Random random;
    private final BlockBox baseArea;

    LumiHistoryBenchmarkScenario(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            LumiBehaviorReport report,
            LumiHistoryBenchmarkConfig config) {
        this.config = config;
        this.report = report;
        actions = new LumiBehaviorActions(singleplayer.getServer(), report);
        operations = new LumiBehaviorOperations(
                context, singleplayer.getServer(), report);
        random = new Random(config.seed());
        baseArea = singleplayer.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            BlockPos origin = player.blockPosition();
            int maximumStartY = player.level().getMaxY() - config.layers();
            int y = Math.min(maximumStartY,
                    Math.max(origin.getY() + 48, 192));
            int minX = origin.getX() - config.baseSize() / 2;
            int minZ = origin.getZ() - config.baseSize() / 2;
            return new BlockBox(
                    minX, y, minZ,
                    minX + config.baseSize() - 1,
                    y + config.layers() - 1,
                    minZ + config.baseSize() - 1);
        });
    }

    void run(LumiUiTestDriver ui) throws IOException {
        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        report.event("benchmark", "configuration", "started", 0, 0,
                config.describe() + ";baseArea=" + describe(baseArea));

        Path repository = operations.repository();
        LumiRepositoryMetrics.Snapshot previous = metrics.capture(repository);
        recordStorage("initial", previous, 0);
        List<CommitId> commits = new ArrayList<>();
        commits.add(operations.activeCommit());

        actions.worldEditRandomVolume("benchmark_base_edit", baseArea, 0);
        commits.add(operations.save("benchmark-base"));
        previous = recordStorage("save-base", repository, previous.bytes());
        recordMemory("save-base");

        for (int index = 1; index <= config.commits(); index++) {
            BlockBox change = nextChangeArea();
            String suffix = String.format("%03d", index);
            actions.worldEditRandomVolume(
                    "benchmark_change_edit_" + suffix, change, index);
            commits.add(operations.save("benchmark-" + suffix));
            if (index % config.measureEvery() == 0
                    || index == config.commits()) {
                previous = recordStorage(
                        "save-" + suffix, repository, previous.bytes());
                recordMemory("save-" + suffix);
            }
        }

        int restoreNumber = 0;
        for (int index : restoreIndices(commits.size(), config.restoreSamples())) {
            String name = String.format("%02d-index-%03d", ++restoreNumber, index);
            operations.restore(name, commits.get(index));
            previous = recordStorage(
                    "restore-" + name, repository, previous.bytes());
            recordMemory("restore-" + name);
        }
        report.event("benchmark", "configuration", "succeeded", 0, 0,
                config.describe() + ";versions=" + commits.size());
    }

    private BlockBox nextChangeArea() {
        int range = config.baseSize() - config.changeSize() + 1;
        int minX = baseArea.minX() + random.nextInt(range);
        int minZ = baseArea.minZ() + random.nextInt(range);
        return new BlockBox(
                minX, baseArea.minY(), minZ,
                minX + config.changeSize() - 1, baseArea.maxY(),
                minZ + config.changeSize() - 1);
    }

    private LumiRepositoryMetrics.Snapshot recordStorage(
            String name, Path repository, long previousBytes) throws IOException {
        LumiRepositoryMetrics.Snapshot snapshot = metrics.capture(repository);
        recordStorage(name, snapshot, previousBytes);
        return snapshot;
    }

    private void recordStorage(
            String name,
            LumiRepositoryMetrics.Snapshot snapshot,
            long previousBytes) {
        report.event("storage", name, "measured", 0,
                snapshot.measurementMillis(), snapshot.describe(previousBytes));
    }

    private void recordMemory(String name) {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        report.event("memory", name, "sampled", 0, 0,
                "usedBytes=" + used + ";committedBytes=" + runtime.totalMemory()
                        + ";maxBytes=" + runtime.maxMemory());
    }

    static List<Integer> restoreIndices(int versionCount, int samples) {
        int last = versionCount - 1;
        LinkedHashSet<Integer> indices = new LinkedHashSet<>();
        for (int sample = 0; sample < samples; sample++) {
            double fraction = sample / (double) (samples - 1);
            indices.add(last - (int) Math.round(last * fraction));
        }
        indices.add(0);
        return List.copyOf(indices);
    }

    private static String describe(BlockBox area) {
        return area.minX() + "," + area.minY() + "," + area.minZ()
                + ".." + area.maxX() + "," + area.maxY() + "," + area.maxZ();
    }
}
