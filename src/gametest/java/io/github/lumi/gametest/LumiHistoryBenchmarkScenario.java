package io.github.lumi.gametest;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/** Creates dense random history and measures real UI history workflows. */
final class LumiHistoryBenchmarkScenario {
    private static final int EDIT_TILE_SIZE = 256;
    private static final int STORED_FIXTURE_OFFSET = 2_048;
    private static final int TILES_PER_DURABILITY_BARRIER = 4;
    private static final long MAX_IMMEDIATE_SAVE_MILLIS = 6_800;
    private static final List<EditSize> PLAYER_SCALE_30 = List.of(
            new EditSize(1, 1, 1),
            new EditSize(2, 1, 1),
            new EditSize(2, 2, 1),
            new EditSize(2, 2, 2),
            new EditSize(4, 2, 2),
            new EditSize(4, 4, 2),
            new EditSize(4, 4, 4),
            new EditSize(8, 4, 4),
            new EditSize(8, 8, 4),
            new EditSize(8, 8, 8),
            new EditSize(16, 8, 8),
            new EditSize(16, 16, 8),
            new EditSize(16, 16, 16),
            new EditSize(24, 16, 16),
            new EditSize(32, 16, 16),
            new EditSize(32, 24, 16),
            new EditSize(32, 32, 16),
            new EditSize(48, 32, 16),
            new EditSize(64, 32, 16),
            new EditSize(64, 48, 16),
            new EditSize(64, 64, 16),
            new EditSize(96, 64, 16),
            new EditSize(128, 64, 16),
            new EditSize(128, 96, 16),
            new EditSize(128, 128, 16),
            new EditSize(192, 128, 16),
            new EditSize(256, 128, 16),
            new EditSize(256, 256, 16),
            new EditSize(512, 256, 16),
            new EditSize(512, 512, 16));
    private final ClientGameTestContext context;
    private final TestSingleplayerContext singleplayer;
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
        this.context = context;
        this.singleplayer = singleplayer;
        this.config = config;
        this.report = report;
        operations = new LumiBehaviorOperations(
                context, singleplayer.getServer(), report);
        fixture = new LumiDenseSectionFixture(
                context, singleplayer.getServer(), report);
        random = new Random(config.seed());
        baseArea = singleplayer.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            BlockPos origin = player.blockPosition();
            int maximumStartY = player.level().getMaxY() - config.layers();
            int y = Math.min(maximumStartY,
                    Math.max(origin.getY() + 48, 192));
            int offset = config.chunkPath().requiresUnloadedFixture()
                    ? STORED_FIXTURE_OFFSET : 0;
            int minX = origin.getX() + offset - config.baseSize() / 2;
            int minZ = origin.getZ() + offset - config.baseSize() / 2;
            if (config.chunkPath().requiresUnloadedFixture()) {
                minX = Math.floorDiv(minX, 16) * 16;
                minZ = Math.floorDiv(minZ, 16) * 16;
            }
            return new BlockBox(
                    minX, y, minZ,
                    minX + config.baseSize() - 1,
                    y + config.layers() - 1,
                    minZ + config.baseSize() - 1);
        });
    }

    void run(LumiUiTestDriver ui) throws IOException {
        if (config.profile()
                == LumiHistoryBenchmarkConfig.Profile.PLAYER_SCALE_30) {
            runPlayerScale(ui);
            return;
        }
        PreparedHistory history = prepareHistory(ui, false);
        List<LumiRestoreMeasurement> restores = new ArrayList<>();
        LumiRepositoryMetrics.Snapshot previous = history.storage();
        List<Integer> restoreOrder;
        if (config.chunkPath().requiresUnloadedFixture()) {
            fixture.awaitUnloaded("stored_chunks_ready", baseArea);
            restoreOrder = List.of(0);
        } else {
            restoreOrder = restoreIndices(
                    history.commits().size(), config.restoreSamples());
        }
        int restoreNumber = 0;
        for (int index : restoreOrder) {
            String name = String.format("%02d-index-%03d", ++restoreNumber, index);
            LumiRestoreMeasurement restore =
                    operations.measureRestore(name, history.commits().get(index));
            restores.add(restore);
            report.event("restore_metrics", name, "measured", 0,
                    restore.totalMillis(), restore.describe());
            previous = recordStorage(
                    "restore-" + name, history.repository(), previous.bytes());
            recordMemory("restore-" + name);
        }
        verifyPerformance("restore", restores);
        report.event("benchmark", "configuration", "succeeded", 0, 0,
                config.describe() + ";versions=" + history.commits().size());
    }

    private void runPlayerScale(LumiUiTestDriver ui) throws IOException {
        PreparedHistory history = preparePlayerScale(ui);
        var checks = new LumiBehaviorChecks(context, singleplayer, report);
        List<CommitId> commits = history.commits();
        String restoreBranch = operations.activeBranch().value();
        List<BranchRef> branches = List.of(
                operations.createBranch("scale-initial", commits.getFirst()),
                operations.createBranch("scale-middle", commits.get(15)),
                operations.createBranch("scale-latest", commits.getLast()));
        List<LumiRestoreMeasurement> measurements = new ArrayList<>();
        LumiRepositoryMetrics.Snapshot previous = history.storage();
        List<Integer> restoreOrder = playerScaleRestoreIndices(
                commits.size(), config.restoreSamples(), config.seed());

        int number = 0;
        for (int index : restoreOrder) {
            String name = String.format(
                    "scale-restore-%02d-index-%02d", ++number, index);
            LumiRestoreMeasurement measurement =
                    operations.measureRestore(name, commits.get(index));
            measurements.add(measurement);
            report.event("restore_metrics", name, "measured", 0,
                    measurement.totalMillis(), measurement.describe());
            checks.assertValue(name + "_active_branch",
                    operations.activeBranch().value(), restoreBranch);
            checks.assertValue(name + "_active_head",
                    operations.activeCommit().hex(), commits.get(index).hex());
            previous = recordStorage(
                    name, history.repository(), previous.bytes());
            recordMemory(name);
        }

        List<BranchRef> branchOrder = playerScaleBranchOrder(branches, config.seed());
        for (int index = 0; index < branchOrder.size(); index++) {
            BranchRef branch = branchOrder.get(index);
            String name = String.format(
                    "scale-branch-%02d-%s", index + 1, branch.name().value());
            LumiRestoreMeasurement measurement =
                    operations.measureBranchSwitch(name, branch.name());
            measurements.add(measurement);
            report.event("restore_metrics", name, "measured", 0,
                    measurement.totalMillis(), measurement.describe());
            assertActive(checks, name, branch);
            previous = recordStorage(
                    name, history.repository(), previous.bytes());
            recordMemory(name);
        }
        checks.finish();
        verifyPerformance("player-scale-30", measurements);
        report.event("benchmark", "configuration", "succeeded", 0, 0,
                config.describe() + ";builderCommits="
                        + PLAYER_SCALE_30.size() + ";versions="
                        + commits.size() + ";protectedBranchHeads=3"
                        + ";directRestores=" + restoreOrder.size()
                        + ";branchSwitches=" + branchOrder.size());
    }

    private PreparedHistory preparePlayerScale(LumiUiTestDriver ui)
            throws IOException {
        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        ui.disablePreviewGeneration();
        report.event("benchmark", "configuration", "started", 0, 0,
                config.describe() + ";previewGeneration=false;baseArea="
                        + describe(baseArea));

        fixture.markBaseline("benchmark_initial_marker");
        CommitId initial = measureImmediateSave(
                "benchmark-initial", operations.pendingKeyCount());
        Path repository = operations.repository();
        LumiRepositoryMetrics.Snapshot previous = metrics.capture(repository);
        recordStorage("initial", previous, 0);
        List<CommitId> commits = new ArrayList<>(31);
        commits.add(initial);
        var actions = new LumiBehaviorActions(singleplayer.getServer(), report);

        for (int index = 0; index < PLAYER_SCALE_30.size(); index++) {
            EditSize size = PLAYER_SCALE_30.get(index);
            BlockBox area = size.at(baseArea);
            String suffix = String.format("%02d", index + 1);
            long started = System.nanoTime();
            actions.worldEditRandomVolume(
                    "player_scale_edit_" + suffix, area, index);
            long expectedKeys = sectionCount(area);
            int actualKeys = operations.pendingBuilderKeyCount();
            if (actualKeys != expectedKeys) {
                throw new AssertionError("player_scale_edit_" + suffix
                        + " dirtied " + actualKeys
                        + " history keys; expected " + expectedKeys);
            }
            report.event("fixture", "player_scale_edit_" + suffix + "_ready",
                    "succeeded", 0,
                    (System.nanoTime() - started) / 1_000_000,
                    "blocks=" + size.blocks() + ";historyKeys=" + actualKeys
                            + ";area=" + describe(area));
            commits.add(measureImmediateSave(
                    "player-scale-" + suffix, operations.pendingKeyCount()));
            if ((index + 1) % config.measureEvery() == 0
                    || index + 1 == PLAYER_SCALE_30.size()) {
                previous = recordStorage(
                        "save-" + suffix, repository, previous.bytes());
                recordMemory("save-" + suffix);
            }
        }
        return new PreparedHistory(
                commits, repository, previous,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private CommitId measureImmediateSave(String name, int pendingKeys)
            throws IOException {
        long started = System.nanoTime();
        CommitId commit = operations.save(name);
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        report.event("save_metrics", name, "measured", 0, elapsed,
                "pendingKeys=" + pendingKeys + ";durability=save-owned");
        if (elapsed > MAX_IMMEDIATE_SAVE_MILLIS) {
            throw new AssertionError(name + " immediate Save took " + elapsed
                    + " ms; limit=" + MAX_IMMEDIATE_SAVE_MILLIS
                    + " ms; pendingKeys=" + pendingKeys);
        }
        return commit;
    }

    BranchFixture prepareBranchSwitch(LumiUiTestDriver ui) throws IOException {
        PreparedHistory history = prepareHistory(ui, true);
        var checks = new LumiBehaviorChecks(context, singleplayer, report);
        CommitId initial = history.commits().getFirst();
        CommitId latest = history.commits().getLast();
        BranchRef branchA = history.branchA().orElseThrow();
        checks.assertValue("branch_a_head_created",
                branchA.commit().hex(), initial.hex());
        BranchRef branchB = operations.createBranch("benchmark-b");
        checks.assertValue("branch_b_head_created",
                branchB.commit().hex(), latest.hex());

        operations.switchBranch("branch_setup_b", branchB.name());
        BranchEndpoint endpointB = new BranchEndpoint(
                branchB, history.latestSnapshot().orElseThrow());
        assertActive(checks, "branch_setup_b", endpointB);
        checks.assertSnapshot("branch_setup_b", List.of(baseArea),
                endpointB.snapshot());
        checks.finish();
        awaitStoredFixture("stored_chunks_ready");

        LumiRepositoryMetrics.Snapshot storage = recordStorage(
                "branch-setup", history.repository(), history.storage().bytes());
        recordMemory("branch-setup");
        return new BranchFixture(
                new BranchEndpoint(
                        branchA, history.initialSnapshot().orElseThrow()),
                endpointB, baseArea, storage.bytes());
    }

    void runBranchSwitch(
            LumiUiTestDriver ui,
            BranchFixture branchFixture) throws IOException {
        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        if (!baseArea.equals(branchFixture.area())) {
            throw new AssertionError("Branch benchmark area changed after reopen");
        }
        var checks = new LumiBehaviorChecks(context, singleplayer, report);
        assertActive(checks, "reopened_branch_b", branchFixture.b());
        checks.finish();
        List<LumiRestoreMeasurement> measurements = new ArrayList<>();
        Path repository = operations.repository();
        long previousBytes = branchFixture.repositoryBytes();

        previousBytes = measureBranchSwitch(
                "cold-b-to-a", branchFixture.a(), checks,
                measurements, repository, previousBytes);
        checks.finish();
        awaitStoredFixture("stored_after_cold_b_to_a");
        operations.switchBranch("prime-a-to-b", branchFixture.b().ref().name());
        assertActive(checks, "prime_a_to_b", branchFixture.b());
        checks.assertSnapshot("prime_a_to_b", List.of(baseArea),
                branchFixture.b().snapshot());
        checks.finish();
        awaitStoredFixture("stored_after_prime_a_to_b");
        previousBytes = measureBranchSwitch(
                "warm-b-to-a", branchFixture.a(), checks,
                measurements, repository, previousBytes);
        checks.finish();
        awaitStoredFixture("stored_after_warm_b_to_a");
        measureBranchSwitch(
                "warm-a-to-b", branchFixture.b(), checks,
                measurements, repository, previousBytes);

        checks.finish();
        verifyPerformance("branch-switch", measurements);
        report.event("benchmark", "configuration", "succeeded", 0, 0,
                config.describe() + ";versions=" + (config.commits() + 2));
    }

    private PreparedHistory prepareHistory(
            LumiUiTestDriver ui,
            boolean captureEndpoints) throws IOException {
        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        ui.disablePreviewGeneration();
        if (config.chunkPath().requiresUnloadedFixture()) {
            ui.disableEntityRestore();
        }
        report.event("benchmark", "configuration", "started", 0, 0,
                config.describe() + ";previewGeneration=false;baseArea="
                        + describe(baseArea));

        fixture.markBaseline("benchmark_initial_marker");
        operations.awaitDurability("benchmark_initial_world");
        SavedEndpoint initial = save("benchmark-initial", captureEndpoints);
        int initialBuilderKeys = operations.pendingBuilderKeyCount();
        if (initialBuilderKeys != 0) {
            throw new AssertionError("Initial benchmark Save left "
                    + initialBuilderKeys + " pending builder keys");
        }
        Path repository = operations.repository();
        LumiRepositoryMetrics.Snapshot previous = metrics.capture(repository);
        recordStorage("initial", previous, 0);
        List<CommitId> commits = new ArrayList<>();
        commits.add(initial.commit());
        Optional<BranchRef> branchA = captureEndpoints
                ? Optional.of(operations.createBranch("benchmark-a"))
                : Optional.empty();

        editRandomVolume("benchmark_base_edit", baseArea, 0);
        commits.add(operations.save("benchmark-base"));
        previous = recordStorage("save-base", repository, previous.bytes());
        recordMemory("save-base");

        for (int index = 1; index <= config.commits(); index++) {
            BlockBox change = nextChangeArea();
            String suffix = String.format("%03d", index);
            editRandomVolume(
                    "benchmark_change_edit_" + suffix, change, index);
            boolean latest = captureEndpoints && index == config.commits();
            SavedEndpoint saved = save("benchmark-" + suffix, latest);
            commits.add(saved.commit());
            if (latest) {
                previous = recordStorage(
                        "save-" + suffix, repository, previous.bytes());
                recordMemory("save-" + suffix);
                return new PreparedHistory(
                        commits, repository, previous, initial.snapshot(),
                        saved.snapshot(), branchA);
            }
            if (index % config.measureEvery() == 0
                    || index == config.commits()) {
                previous = recordStorage(
                        "save-" + suffix, repository, previous.bytes());
                recordMemory("save-" + suffix);
            }
        }
        return new PreparedHistory(
                commits, repository, previous,
                initial.snapshot(), Optional.empty(), branchA);
    }

    private SavedEndpoint save(String name, boolean captureSnapshot)
            throws IOException {
        if (!captureSnapshot) {
            return new SavedEndpoint(
                    operations.save(name), Optional.empty());
        }
        LumiBehaviorOperations.SavedBoundary saved =
                operations.save(name, List.of(baseArea));
        return new SavedEndpoint(
                saved.commit(), Optional.of(saved.snapshot()));
    }

    private long measureBranchSwitch(
            String name,
            BranchEndpoint endpoint,
            LumiBehaviorChecks checks,
            List<LumiRestoreMeasurement> measurements,
            Path repository,
            long previousBytes) throws IOException {
        LumiRestoreMeasurement measurement = operations.measureBranchSwitch(
                name, endpoint.ref().name());
        measurements.add(measurement);
        report.event("restore_metrics", name, "measured", 0,
                measurement.totalMillis(), measurement.describe());
        assertActive(checks, name, endpoint);
        checks.assertSnapshot(name, List.of(baseArea), endpoint.snapshot());
        LumiRepositoryMetrics.Snapshot storage = recordStorage(
                "branch-switch-" + name, repository, previousBytes);
        recordMemory("branch-switch-" + name);
        return storage.bytes();
    }

    private void awaitStoredFixture(String name) {
        if (config.chunkPath().requiresUnloadedFixture()) {
            fixture.awaitUnloaded(name, baseArea);
        }
    }

    private void assertActive(
            LumiBehaviorChecks checks,
            String name,
            BranchEndpoint endpoint) throws IOException {
        assertActive(checks, name, endpoint.ref());
    }

    private void assertActive(
            LumiBehaviorChecks checks,
            String name,
            BranchRef ref) throws IOException {
        checks.assertValue(name + "_active_branch",
                operations.activeBranch().value(), ref.name().value());
        checks.assertValue(name + "_active_head",
                operations.activeCommit().hex(), ref.commit().hex());
    }

    private void verifyPerformance(
            String operation,
            List<LumiRestoreMeasurement> restores) {
        try {
            LumiRestorePerformanceGate.Result result =
                    LumiRestorePerformanceGate.verify(config, restores);
            report.event("performance_gate", operation, "succeeded", 0, 0,
                    result.describe());
        } catch (AssertionError failed) {
            report.event("performance_gate", operation, "failed", 0, 0,
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
        int actualKeys = operations.pendingBuilderKeyCount();
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

    static List<Integer> playerScaleRestoreIndices(
            int versionCount, int samples, long seed) {
        int last = versionCount - 1;
        List<Integer> indices = new ArrayList<>();
        Random seeded = new Random(seed);
        while (indices.size() < samples - 1) {
            int candidate = 1 + seeded.nextInt(last - 1);
            if (!indices.contains(candidate)) {
                indices.add(candidate);
            }
        }
        indices.sort(java.util.Comparator.reverseOrder());
        indices.add(0);
        return List.copyOf(indices);
    }

    private static List<BranchRef> playerScaleBranchOrder(
            List<BranchRef> branches, long seed) {
        List<BranchRef> order = new ArrayList<>(6);
        BranchRef latest = branches.getLast();
        Random seeded = new Random(seed);
        order.add(latest);
        List<BranchRef> remainder = new ArrayList<>(branches);
        remainder.remove(latest);
        Collections.shuffle(remainder, seeded);
        order.addAll(remainder);
        List<BranchRef> secondCycle = new ArrayList<>(branches);
        Collections.shuffle(secondCycle, seeded);
        if (secondCycle.getFirst().equals(order.getLast())) {
            Collections.rotate(secondCycle, 1);
        }
        order.addAll(secondCycle);
        return List.copyOf(order);
    }

    private static String describe(BlockBox area) {
        return area.minX() + "," + area.minY() + "," + area.minZ()
                + ".." + area.maxX() + "," + area.maxY() + "," + area.maxZ();
    }

    record BranchFixture(
            BranchEndpoint a,
            BranchEndpoint b,
            BlockBox area,
            long repositoryBytes) { }

    record BranchEndpoint(
            BranchRef ref,
            LumiWorldSnapshot snapshot) { }

    private record PreparedHistory(
            List<CommitId> commits,
            Path repository,
            LumiRepositoryMetrics.Snapshot storage,
            Optional<LumiWorldSnapshot> initialSnapshot,
            Optional<LumiWorldSnapshot> latestSnapshot,
            Optional<BranchRef> branchA) { }

    private record SavedEndpoint(
            CommitId commit,
            Optional<LumiWorldSnapshot> snapshot) { }

    private record EditSize(int width, int depth, int layers) {
        BlockBox at(BlockBox base) {
            return new BlockBox(
                    base.minX(), base.minY(), base.minZ(),
                    base.minX() + width - 1,
                    base.minY() + layers - 1,
                    base.minZ() + depth - 1);
        }

        long blocks() {
            return (long) width * depth * layers;
        }
    }
}
