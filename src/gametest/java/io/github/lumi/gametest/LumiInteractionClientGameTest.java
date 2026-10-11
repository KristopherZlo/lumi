package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import java.io.IOException;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** First/repeated mouse interactions at world, section, Save and Restore boundaries. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiInteractionClientGameTest implements FabricClientGameTest {
    private BlockPos origin;

    @Override public void runTest(ClientGameTestContext context) {
        if (!LumiClientTestSuite.includes(LumiClientTestSuite.INTERACTION)
                || LumiHistoryBenchmarkConfig.enabled()) return;
        boolean windowActive = context.computeOnClient(client -> client.isWindowActive());
        try (var ignored = LumiUiScaleTestScope.readableViewport()) {
            LumiClientBehaviorWorld.runWithReopen(context, "interaction-latency",
                    this::exercise, this::reopened);
        } finally {
            context.runOnClient(client -> client.setWindowActive(windowActive));
        }
    }

    private void exercise(ClientGameTestContext context, TestSingleplayerContext world,
            LumiBehaviorReport report) throws IOException {
        var server = world.getServer();
        var ui = new LumiUiTestDriver(context);
        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        origin = server.computeOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            BlockPos start = new BlockPos(player.blockPosition().getX(), 170,
                    player.blockPosition().getZ());
            var runtime = LumiMod.serverRuntime().find(player.level()).orElseThrow();
            try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player.getUUID())) {
                for (int offset : new int[] {0, 64}) {
                    for (int index = 0; index < 3; index++) {
                        BlockPos position = start.offset(offset + index * 16, 0, 0);
                        player.level().setBlockAndUpdate(position.below(), Blocks.STONE.defaultBlockState());
                        player.level().setBlockAndUpdate(position.above(), Blocks.AIR.defaultBlockState());
                        player.level().setBlockAndUpdate(position, switch (index) {
                            case 0 -> Blocks.CHEST.defaultBlockState();
                            case 1 -> Blocks.STONE.defaultBlockState();
                            default -> Blocks.LEVER.defaultBlockState()
                                    .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
                        });
                    }
                }
            }
            return start;
        });
        var operations = new LumiBehaviorOperations(context, server, report);
        var driver = new LumiInteractionTestDriver(context, server, report);
        runPhase(driver, "created", origin);
        operations.awaitDurability("interaction_fixture");
        CommitId baseline = operations.save("interaction-fixture");
        runPhase(driver, "after_save", origin);
        runPhase(driver, "new_section", origin.offset(64, 0, 0));
        changeMarker(world, Blocks.GOLD_BLOCK.defaultBlockState());
        operations.awaitDurability("interaction_marker");
        operations.save("interaction-marker");
        operations.restore("interaction-fixture", baseline);
        runPhase(driver, "after_restore", origin);
        // Leave no pending origin from the repetitions before reopening the runtime.
        changeMarker(world, Blocks.DIAMOND_BLOCK.defaultBlockState());
        operations.awaitDurability("interaction_reopen_checkpoint");
        operations.save("interaction-reopen-checkpoint");
        operations.restore("interaction-reopen-fixture", baseline);
        report.assertNoRuntimeFailures();
    }

    private void reopened(ClientGameTestContext context, TestSingleplayerContext world,
            LumiBehaviorReport report) {
        new LumiUiTestDriver(context).completeOnboardingIfShown();
        runPhase(new LumiInteractionTestDriver(context, world.getServer(), report),
                "reopened", origin);
        report.assertNoRuntimeFailures();
        report.event("gate", "single_click_interactions", "succeeded", 0, 0,
                "120 actions;5 boundaries;4 interaction types;no retries");
    }

    private static void runPhase(LumiInteractionTestDriver driver, String phase, BlockPos start) {
        driver.exercise(phase, start, start.offset(16, 0, 0), start.offset(32, 0, 0));
    }

    private void changeMarker(TestSingleplayerContext world, BlockState state) {
        world.getServer().runOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            var runtime = LumiMod.serverRuntime().find(player.level()).orElseThrow();
            try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player.getUUID())) {
                player.level().setBlockAndUpdate(origin.offset(0, 0, 4), state);
            }
        });
    }
}
