package io.github.lumi.gametest;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import java.io.IOException;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/** The requested seed-710 TNT, history, WorldEdit, branch and merge workflow. */
final class LumiBehaviorScenario {
    private final LumiBehaviorChecks checks;
    private final LumiBehaviorOperations operations;
    private final LumiBehaviorActions actions;
    private final BlockPos origin;
    private final List<BlockPos> tnt;
    private final List<BlockBox> tntArea;
    private final List<BlockBox> quickRollbackAmbientArea;
    private final List<BlockBox> unmodifiedControlArea;
    private final List<BlockBox> platformArea;
    private final List<BlockBox> allAreas;

    LumiBehaviorScenario(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            LumiBehaviorReport report) {
        checks = new LumiBehaviorChecks(context, singleplayer, report);
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
        tnt = actions.planTwentyTnt(origin);
        int lowestTnt = tnt.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int highestTnt = tnt.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        tntArea = List.of(new BlockBox(
                origin.getX() - 24, Math.max(world.minY(), lowestTnt - 16),
                origin.getZ() - 24,
                origin.getX() + 24, Math.min(world.maxY(), highestTnt + 16),
                origin.getZ() + 24));
        BlockBox initialVolume = new BlockBox(
                origin.getX() - 24, world.minY(), origin.getZ() - 24,
                origin.getX() + 24, world.maxY(), origin.getZ() + 24);
        quickRollbackAmbientArea = List.of(new BlockBox(
                initialVolume.minX(), initialVolume.minY(), initialVolume.minZ(),
                initialVolume.maxX(), tntArea.getFirst().minY() - 1,
                initialVolume.maxZ()));
        unmodifiedControlArea = List.of(new BlockBox(
                initialVolume.minX(), initialVolume.minY(), initialVolume.minZ(),
                initialVolume.minX(), initialVolume.maxY(), initialVolume.maxZ()));
        platformArea = List.of(new BlockBox(
                origin.getX() - 40, origin.getY() + 60, origin.getZ() - 40,
                origin.getX() + 40, origin.getY() + 140, origin.getZ() + 40));
        allAreas = List.of(initialVolume, platformArea.getFirst());
    }

    void run() throws IOException {
        checks.awaitQuiescence("world", allAreas);
        var initialSave = operations.save("initial", allAreas);
        CommitId initialCommit = initialSave.commit();
        BranchName mainBranch = operations.activeBranch();
        LumiWorldSnapshot initial = initialSave.snapshot();
        LumiWorldSnapshot initialTnt = checks.snapshot(
                "initial_tnt_control", tntArea);
        LumiWorldSnapshot initialControl = checks.snapshot(
                "initial_unmodified_control", unmodifiedControlArea);

        actions.placeTwentyTnt(tnt);
        LumiWorldSnapshot tntPlaced = checks.snapshot("tnt_placed", tntArea);
        actions.powerTnt(tnt.getFirst());
        checks.waitTicks("first_fuse_1s", 20);
        checks.snapshot("first_fuse_1s", tntArea);
        operations.undo("first_fuse");
        checks.assertSnapshot("undo_first_fuse", tntArea, tntPlaced);

        operations.redo("first_fuse");
        checks.waitTicks("redo_explosion_5s", 100);
        checks.snapshot("redo_explosion_5s", tntArea);
        operations.undo("explosion_after_5s");
        checks.assertSnapshot("undo_explosion_after_5s", tntArea, tntPlaced);

        actions.powerTnt(tnt.getFirst());
        checks.waitTicks("second_explosion_20s", 400);
        checks.snapshot("second_explosion_20s", tntArea);
        checks.assertSnapshot("before_quick_rollback_unmodified_control",
                unmodifiedControlArea, initialControl);
        var quickRollback = operations.quickRollback(
                "initial", quickRollbackAmbientArea);
        checks.assertSnapshot("quick_rollback_preserves_ambient",
                quickRollback.after(), quickRollback.before());
        checks.assertSnapshot("quick_rollback_initial_tnt", tntArea, initialTnt);
        checks.assertSnapshot("after_quick_rollback_unmodified_control",
                unmodifiedControlArea, initialControl);
        checks.screenshot("quick-rollback-initial");

        LumiBehaviorActions.Platform platform = actions.buildPlatform(origin);
        checks.snapshot("platform_built", platformArea);
        CommitId platformCommit = operations.save("platform");
        LumiWorldSnapshot platformSaved = checks.snapshot("platform_saved", platformArea);
        actions.breakPlatformByHand(platform);
        checks.snapshot("platform_broken", platformArea);
        operations.restore("platform", platformCommit);
        checks.assertSnapshot("platform_restored", platformArea, platformSaved);

        actions.worldEditCylinder();
        checks.snapshot("worldedit_cylinder", platformArea);
        CommitId test1Commit = operations.save("test1");
        LumiWorldSnapshot test1 = checks.snapshot("test1_saved", platformArea);

        actions.worldEditGreen();
        checks.snapshot("worldedit_green", platformArea);
        operations.save("test2");
        LumiWorldSnapshot test2 = checks.snapshot("test2_saved", platformArea);
        var test2Branch = operations.createBranch("behavior-test2");

        actions.placeAndIgniteFiveTnt(platform.center());
        checks.waitTicks("platform_explosion_8s", 160);
        checks.snapshot("platform_explosion_8s", platformArea);
        operations.restore("test1_first", test1Commit);
        checks.assertSnapshot("restore_test1_first", platformArea, test1);
        operations.switchBranch("test2", test2Branch.name());
        checks.assertSnapshot("restore_test2", platformArea, test2);

        operations.restore("test1_for_branch", test1Commit);
        checks.assertSnapshot("restore_test1_for_branch", platformArea, test1);
        var branch = operations.createBranch("behavior-test1");
        operations.switchBranch("behavior-test1", branch.name());
        checks.assertSnapshot("switch_behavior_test1", platformArea, test1);

        actions.worldEditReplaceNear();
        checks.snapshot("branch_replace_near", platformArea);
        operations.save("branch_test1");
        checks.snapshot("branch_test1_saved", platformArea);
        actions.buildOakCube(platform.center());
        checks.snapshot("branch_oak_cube", platformArea);
        operations.save("branch_test2");
        LumiWorldSnapshot branchTest2 = checks.snapshot(
                "branch_test2_saved", platformArea);

        operations.switchBranch("main", mainBranch);
        operations.merge("branch_into_main", branch.name());
        checks.assertSnapshot("merge_into_main", platformArea, branchTest2);
        checks.screenshot("merged-branch");

        operations.restore("test1_final", test1Commit);
        checks.assertSnapshot("restore_test1_final", platformArea, test1);
        operations.switchBranch("branch_test2_final", branch.name());
        checks.assertSnapshot("restore_branch_test2_final", platformArea, branchTest2);
        operations.restore("initial_final", initialCommit);
        checks.assertSnapshot("restore_initial_final", allAreas, initial);
        checks.screenshot("restored-initial");
        checks.finish();
    }

    private record WorldOrigin(BlockPos position, int minY, int maxY) { }
}
