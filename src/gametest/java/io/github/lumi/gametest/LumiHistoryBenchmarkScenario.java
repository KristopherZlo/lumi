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
    private static final int EDIT_TILE_SIZE = 256;
    private static final int TILES_PER_DURABILITY_BARRIER = 4;
    private final LumiHistoryBenchmarkConfig config;
    private final LumiBehaviorReport report;
    private final LumiBehaviorOperations operations;
    private final LumiDenseSectionFixture fixture;
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
        operations = new LumiBehaviorOperations(
                context, singleplayer.getServer(), report);
        fixture = new LumiDenseSectionFixture(singleplayer.getServer(), report);
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
        ui.disablePreviewGeneration();
        report.event("benchmark", "configuration", "started", 0, 0,
                config.describe() + ";previewGeneration=false;baseArea="
                        + describe(baseArea));

        Path repository = operations.repository();
        LumiRepositoryMetrics.Snapshot previous = metrics.capture(repository);
        recordStorage("initial", previous, 0);
        List<CommitId> commits = new ArrayList<>();
        List<LumiRestoreMeasurement> restores = new ArrayList<>();
        commits.add(operations.activeCommit());

        editRandomVolume("benchmark_base_edit", baseArea, 0);
        commits.add(operations.save("benchmark-base"));
        previous = recordStorage("save-base", repository, previous.bytes());
        recordMemory("save-base");

        for (int index = 1; index <= config.commits(); index++) {
            BlockBox change = nextChangeArea();
            String suffix = String.format("%03d", index);
            editRandomVolume(
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
            LumiRestoreMeasurement restore =
                    operations.measureRestore(name, commits.get(index));
            restores.add(restore);
            report.event("restore_metrics", name, "measured", 0,
                    restore.totalMillis(), restore.describe());
            previous = recordStorage(
                    "restore-" + name, repository, previous.bytes());
            recordMemory("restore-" + name);
        }
        verifyPerformance(restores);
        report.event("benchmark", "configuration", "succeeded", 0, 0,
                config.describe() + ";versions=" + commits.size());
    }

    private void verifyPerformance(List<LumiRestoreMeasurement> restores) {
        try {
            LumiRestorePerformanceGate.Result result =
                    LumiRestorePerformanceGate.verify(config, restores);
            report.event("performance_gate", "restore", "succeeded", 0, 0,
                    result.describe());
        } catch (AssertionError failed) {
            report.event("performance_gate", "restore", "failed", 0, 0,
                    failed.getMessage());
            throw failed;
        }
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

    private void editRandomVolume(String name, BlockBox area, int paletteOffset) {
        long started = System.nanoTime();
        int tile = 0;
        for (int z = area.minZ(); z <= area.maxZ(); z += EDIT_TILE_SIZE) {
            for (int x = area.minX(); x <= area.maxX(); x += EDIT_TILE_SIZE) {
                BlockBox batch = new BlockBox(
                        x, area.minY(), z,
                        Math.min(area.maxX(), x + EDIT_TILE_SIZE - 1),
                        area.maxY(),
                        Math.min(area.maxZ(), z + EDIT_TILE_SIZE - 1));
                fixture.fill(
                        name + "_tile_" + String.format("%04d", ++tile),
                        batch, paletteOffset + tile);
                if (tile % TILES_PER_DURABILITY_BARRIER == 0) {
                    operations.awaitDurability(name + "_batch_" + tile);
                }
            }
        }
        if (tile % TILES_PER_DURABILITY_BARRIER != 0) {
            operations.awaitDurability(name + "_batch_" + tile);
        }
        long expectedKeys = sectionCount(area);
        int actualKeys = operations.pendingKeyCount();
        if (actualKeys != expectedKeys) {
            throw new AssertionError(name + " dirtied " + actualKeys
                    + " history keys; expected exactly " + expectedKeys);
        }
        report.event("change", name, "succeeded", 0,
                (System.nanoTime() - started) / 1_000_000,
                "tiles=" + tile + ";tileSize=" + EDIT_TILE_SIZE
                        + ";historyKeys=" + actualKeys);
    }

    private static long sectionCount(BlockBox area) {
        long x = Math.floorDiv(area.maxX(), 16)
                - Math.floorDiv(area.minX(), 16) + 1L;
        long y = Math.floorDiv(area.maxY(), 16)
                - Math.floorDiv(area.minY(), 16) + 1L;
        long z = Math.floorDiv(area.maxZ(), 16)
                - Math.floorDiv(area.minZ(), 16) + 1L;
        return x * y * z;
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
        long previewBytes = snapshot.categoryBytes().getOrDefault("previews", 0L);
        if (previewBytes != 0) {
            throw new AssertionError(
                    "Benchmark generated " + previewBytes + " preview bytes");
        }
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
