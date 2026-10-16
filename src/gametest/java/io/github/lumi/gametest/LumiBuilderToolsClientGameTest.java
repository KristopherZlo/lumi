package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import java.io.IOException;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Real client tool packets followed by exact history operations and native reopen. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiBuilderToolsClientGameTest implements FabricClientGameTest {
    private BlockPos origin;
    private List<BlockBox> area;
    private LumiWorldSnapshot expected;

    @Override public void runTest(ClientGameTestContext context) {
        if (!LumiClientTestSuite.includes(LumiClientTestSuite.BUILDER_TOOLS)
                || LumiHistoryBenchmarkConfig.enabled()) return;
        if (!loaded("worldedit") && !loaded("axiom")) return;
        try (var ignored = LumiUiScaleTestScope.readableViewport()) {
            LumiClientBehaviorWorld.runWithReopen(context, "builder-tools",
                    this::exercise, this::reopened);
        }
    }

    private void exercise(ClientGameTestContext context, TestSingleplayerContext world,
            LumiBehaviorReport report) throws IOException {
        var server = world.getServer();
        var ui = new LumiUiTestDriver(context);
        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        origin = server.computeOnServer(minecraft -> {
            minecraft.tickRateManager().setFrozen(true);
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            BlockPos start = player.blockPosition().atY(170);
            var runtime = LumiMod.serverRuntime().find(player.level()).orElseThrow();
            try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player.getUUID())) {
                for (int x = 0; x < 6; x++) {
                    player.level().setBlockAndUpdate(start.offset(x, 0, 0), Blocks.STONE.defaultBlockState());
                }
            }
            return start;
        });
        area = List.of(new BlockBox(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + 5, origin.getY(), origin.getZ()));
        if (loaded("worldedit")) {
            exerciseTool(context, world, report, "worldedit", origin, () -> {
                context.runOnClient(client -> {
                    client.getConnection().sendCommand("/pos1 " + coordinates(origin));
                    client.getConnection().sendCommand("/pos2 " + coordinates(origin.east()));
                    client.getConnection().sendCommand("/set gold_block");
                });
            });
        }
        if (loaded("axiom")) {
            BlockPos target = origin.offset(4, 0, 0);
            exerciseTool(context, world, report, "axiom", target,
                    () -> context.runOnClient(client -> AxiomPackets.send(client, target)));
        }
        expected = new LumiBehaviorChecks(context, world, report).snapshot("tools_final", area);
        report.assertNoRuntimeFailures();
    }

    private void exerciseTool(ClientGameTestContext context, TestSingleplayerContext world,
            LumiBehaviorReport report, String tool, BlockPos target, Runnable edit) throws IOException {
        var server = world.getServer();
        var operations = new LumiBehaviorOperations(context, server, report);
        var checks = new LumiBehaviorChecks(context, world, report);
        operations.awaitDurability(tool + "_baseline");
        var baseline = operations.save(tool + "-before", area);
        edit.run();
        for (int tick = 0; ; tick++) {
            if (server.computeOnServer(minecraft -> {
                var level = minecraft.getPlayerList().getPlayers().getFirst().level();
                return level.getBlockState(target).is(Blocks.GOLD_BLOCK)
                        && level.getBlockState(target.east()).is(Blocks.GOLD_BLOCK);
            })) break;
            if (tick >= 200) throw new AssertionError(tool + " client edit did not reach the server");
            context.waitTick();
        }
        operations.awaitDurability(tool + "_edited");
        var editedState = checks.snapshot(tool + "_edited", area);
        operations.undo(tool);
        checks.assertSnapshot(tool + "_undo", area, baseline.snapshot());
        operations.redo(tool);
        checks.assertSnapshot(tool + "_redo", area, editedState);
        var edited = operations.save(tool + "-after", area);
        var comparison = server.computeOnServer(minecraft -> LumiMod.serverRuntime()
                .find(minecraft.getPlayerList().getPlayers().getFirst().level()).orElseThrow()
                .compare(baseline.commit(), edited.commit()));
        context.waitFor(client -> comparison.isDone());
        var summary = comparison.join();
        if (summary.changedBlocks() != 2) {
            throw new AssertionError(tool + " Compare did not report exactly two changed blocks: " + summary);
        }
        operations.restore(tool + "_before", baseline.commit());
        checks.assertSnapshot(tool + "_restore_before", area, baseline.snapshot());
        operations.restore(tool + "_after", edited.commit());
        checks.assertSnapshot(tool + "_restore_after", area, edited.snapshot());
        checks.finish();
        report.event("gate", tool + "_client_history", "succeeded", 0, 0,
                "client packets;2 blocks;Compare;Undo;Redo;Save;Restore");
    }

    private void reopened(ClientGameTestContext context, TestSingleplayerContext world,
            LumiBehaviorReport report) throws IOException {
        new LumiUiTestDriver(context).completeOnboardingIfShown();
        var checks = new LumiBehaviorChecks(context, world, report);
        checks.assertSnapshot("tools_reopened", area, expected);
        checks.finish();
        report.event("gate", "builder_tools_reopen", "succeeded", 0, 0, "exact world state");
    }

    private static boolean loaded(String mod) {
        return FabricLoader.getInstance().isModLoaded(mod);
    }

    private static String coordinates(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    /** Keeps Axiom linkage out of a WorldEdit-only JVM. */
    private static final class AxiomPackets {
        private static void send(net.minecraft.client.Minecraft client, BlockPos position) {
            var buffer = new com.moulberry.axiom.world_modification.BlockBuffer();
            for (int x = 0; x < 2; x++) buffer.set(position.getX() + x,
                    position.getY(), position.getZ(), Blocks.GOLD_BLOCK.defaultBlockState());
            new com.moulberry.axiom.packets.AxiomServerboundSetBuffer(
                    client.level.dimension(), buffer, 0).send();
        }
    }
}
