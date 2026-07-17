package io.github.lumi.gametest;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/** The requested seed-710 TNT, history, WorldEdit, branch and merge workflow. */
final class LumiBehaviorScenario {
    private final ClientGameTestContext context;
    private final TestSingleplayerContext singleplayer;
    private final LumiBehaviorReport report;
    private final LumiBehaviorOperations operations;
    private final LumiBehaviorActions actions;
    private final BlockPos origin;
    private final List<BlockBox> tntArea;
    private final List<BlockBox> unmodifiedControlArea;
    private final List<BlockBox> platformArea;
    private final List<BlockBox> allAreas;
    private final List<String> failures = new ArrayList<>();

    LumiBehaviorScenario(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            LumiBehaviorReport report) {
        this.context = context;
        this.singleplayer = singleplayer;
        this.report = report;
        operations = new LumiBehaviorOperations(
                context, singleplayer.getServer(), report);
        actions = new LumiBehaviorActions(singleplayer.getServer(), report);
        WorldOrigin world = singleplayer.getServer().computeOnServer(server -> {
            var level = server.getPlayerList().getPlayers().getFirst().level();
            return new WorldOrigin(
                    server.getPlayerList().getPlayers().getFirst().blockPosition(),
                    level.getMinY(), level.getMaxY() - 1);
        });
        origin = world.position();
        BlockBox tntVolume = new BlockBox(
                origin.getX() - 24, world.minY(), origin.getZ() - 24,
                origin.getX() + 24, world.maxY(), origin.getZ() + 24);
        tntArea = List.of(tntVolume);
        unmodifiedControlArea = List.of(new BlockBox(
                tntVolume.minX(), tntVolume.minY(), tntVolume.minZ(),
                tntVolume.minX(), tntVolume.maxY(), tntVolume.maxZ()));
        platformArea = List.of(new BlockBox(
                origin.getX() - 40, origin.getY() + 60, origin.getZ() - 40,
                origin.getX() + 40, origin.getY() + 140, origin.getZ() + 40));
        allAreas = List.of(tntArea.getFirst(), platformArea.getFirst());
    }

    void run() throws IOException {
        snapshot("world_preload", allAreas);
        waitTicks("world_stabilization", 100);
        CommitId initialCommit = operations.activeCommit();
        BranchName mainBranch = operations.activeBranch();
        LumiWorldSnapshot initial = snapshot("initial", allAreas);
        LumiWorldSnapshot initialControl = snapshot(
                "initial_unmodified_control", unmodifiedControlArea);

        var tnt = actions.placeTwentyTnt(origin);
        LumiWorldSnapshot tntPlaced = snapshot("tnt_placed", tntArea);
        actions.powerTnt(tnt.getFirst());
        waitTicks("first_fuse_1s", 20);
        snapshot("first_fuse_1s", tntArea);
        operations.undo("first_fuse");
        assertSnapshot("undo_first_fuse", tntArea, tntPlaced);

        operations.redo("first_fuse");
        waitTicks("redo_explosion_5s", 100);
        snapshot("redo_explosion_5s", tntArea);
        operations.undo("explosion_after_5s");
        assertSnapshot("undo_explosion_after_5s", tntArea, tntPlaced);

        actions.powerTnt(tnt.getFirst());
        waitTicks("second_explosion_20s", 400);
        snapshot("second_explosion_20s", tntArea);
        assertSnapshot("before_quick_rollback_unmodified_control",
                unmodifiedControlArea, initialControl);
        operations.quickRollback();
        assertSnapshot("quick_rollback_initial", allAreas, initial);
        screenshot("quick-rollback-initial");

        LumiBehaviorActions.Platform platform = actions.buildPlatform(origin);
        snapshot("platform_built", platformArea);
        CommitId platformCommit = operations.save("platform");
        LumiWorldSnapshot platformSaved = snapshot("platform_saved", platformArea);
        actions.breakPlatformByHand(platform);
        snapshot("platform_broken", platformArea);
        operations.restore("platform", platformCommit);
        assertSnapshot("platform_restored", platformArea, platformSaved);

        actions.worldEditCylinder();
        snapshot("worldedit_cylinder", platformArea);
        CommitId test1Commit = operations.save("test1");
        LumiWorldSnapshot test1 = snapshot("test1_saved", platformArea);

        actions.worldEditGreen();
        snapshot("worldedit_green", platformArea);
        CommitId test2Commit = operations.save("test2");
        LumiWorldSnapshot test2 = snapshot("test2_saved", platformArea);

        actions.placeAndIgniteFiveTnt(platform.center());
        waitTicks("platform_explosion_8s", 160);
        snapshot("platform_explosion_8s", platformArea);
        operations.restore("test1_first", test1Commit);
        assertSnapshot("restore_test1_first", platformArea, test1);
        operations.restore("test2", test2Commit);
        assertSnapshot("restore_test2", platformArea, test2);

        operations.restore("test1_for_branch", test1Commit);
        assertSnapshot("restore_test1_for_branch", platformArea, test1);
        var branch = operations.createBranch("behavior-test1");
        operations.switchBranch("behavior-test1", branch.name());
        assertSnapshot("switch_behavior_test1", platformArea, test1);

        actions.worldEditReplaceNear();
        snapshot("branch_replace_near", platformArea);
        operations.save("branch_test1");
        snapshot("branch_test1_saved", platformArea);
        actions.buildOakCube(platform.center());
        snapshot("branch_oak_cube", platformArea);
        CommitId branchTest2Commit = operations.save("branch_test2");
        LumiWorldSnapshot branchTest2 = snapshot(
                "branch_test2_saved", platformArea);

        operations.switchBranch("main", mainBranch);
        operations.merge("branch_into_main", branch.name());
        assertSnapshot("merge_into_main", platformArea, branchTest2);
        screenshot("merged-branch");

        operations.restore("test1_final", test1Commit);
        assertSnapshot("restore_test1_final", platformArea, test1);
        operations.restore("branch_test2_final", branchTest2Commit);
        assertSnapshot("restore_branch_test2_final", platformArea, branchTest2);
        operations.restore("initial_final", initialCommit);
        assertSnapshot("restore_initial_final", allAreas, initial);
        screenshot("restored-initial");
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size()
                    + " exact snapshot checks failed: " + String.join(" | ", failures));
        }
    }

    private LumiWorldSnapshot snapshot(String name, List<BlockBox> areas)
            throws IOException {
        return singleplayer.getServer().computeOnServer(server -> {
            var level = server.getPlayerList().getPlayers().getFirst().level();
            return LumiWorldSnapshot.capture(level, areas, report, name);
        });
    }

    private void assertSnapshot(
            String name,
            List<BlockBox> areas,
            LumiWorldSnapshot expected) throws IOException {
        LumiWorldSnapshot actual = snapshot(name, areas);
        try {
            actual.assertMatches(expected, name);
            report.event("assertion", name, "succeeded", 0, 0, "");
        } catch (AssertionError mismatch) {
            failures.add(mismatch.getMessage());
            report.event("assertion", name, "failed", 0, 0,
                    mismatch.getMessage());
        }
    }

    private void waitTicks(String name, int ticks) {
        long started = System.nanoTime();
        context.waitTicks(ticks);
        report.event("wait", name, "completed", ticks,
                (System.nanoTime() - started) / 1_000_000, "");
    }

    private void screenshot(String name) {
        long started = System.nanoTime();
        var path = context.takeScreenshot("lumi-behavior-" + name);
        report.event("screenshot", name, "captured", 0,
                (System.nanoTime() - started) / 1_000_000, path.toString());
    }

    private record WorldOrigin(BlockPos position, int minY, int maxY) { }
}
