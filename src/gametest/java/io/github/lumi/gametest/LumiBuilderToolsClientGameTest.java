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

/** Real client tool entry points followed by exact history operations and native reopen. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiBuilderToolsClientGameTest implements FabricClientGameTest {
    private BlockPos origin;
    private List<BlockBox> area;
    private LumiWorldSnapshot expected;
    private boolean expectedDesert;

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
                origin.getX() + 63, origin.getY() + 4, origin.getZ()));
        var operations = new LumiBehaviorOperations(context, server, report);
        operations.awaitDurability("tools_fixture");
        operations.save("tools-baseline", area);
        for (int attempt = 0; ; attempt++) {
            context.waitTicks(20);
            operations.awaitDurability("tools_settled_" + attempt);
            if (operations.pendingKeyCount() == 0) break;
            if (attempt == 3) throw new AssertionError("Tool fixture did not reach a clean world baseline");
            // Chunk post-processing can finish after the first frozen Save releases its capture.
            // A fixture-only edit keeps this Save eligible when the remaining changes are unowned.
            int current = attempt;
            server.runOnServer(minecraft -> {
                var player = minecraft.getPlayerList().getPlayers().getFirst();
                var runtime = LumiMod.serverRuntime().find(player.level()).orElseThrow();
                try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player.getUUID())) {
                    player.level().setBlockAndUpdate(origin.offset(3, 0, 0),
                            (current % 2 == 0 ? Blocks.DIRT : Blocks.STONE).defaultBlockState());
                }
            });
            operations.save("tools-settled-" + attempt, area);
        }
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
            exerciseBiomes(context, world, report);
            BlockPos target = origin.offset(4, 0, 0);
            exerciseTool(context, world, report, "axiom", target,
                    () -> context.runOnClient(client -> AxiomClientEdits.apply(target)));
            new LumiAxiomEntityClientScenario().run(context, world, report, origin, area);
            new LumiAxiomSplitDispatchClientScenario().run(context, world, report, origin, area);
            new LumiAxiomEditorClientGameTest().run(context, report);
        }
        expected = new LumiBehaviorChecks(context, world, report).snapshot("tools_final", area);
        if (loaded("axiom")) assertBiomes(world);
        report.assertNoRuntimeFailures();
    }

    private void exerciseTool(ClientGameTestContext context, TestSingleplayerContext world,
            LumiBehaviorReport report, String tool, BlockPos target, Runnable edit) throws IOException {
        var server = world.getServer();
        var operations = new LumiBehaviorOperations(context, server, report);
        var checks = new LumiBehaviorChecks(context, world, report);
        operations.awaitDurability(tool + "_baseline");
        if (operations.pendingKeyCount() != 0) {
            throw new AssertionError(tool + " baseline has unsaved world mutations");
        }
        var baseline = new LumiBehaviorOperations.SavedBoundary(
                operations.activeCommit(), checks.snapshot(tool + "_baseline", area));
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
        if (tool.equals("axiom")) assertBiomes(world);
        checks.finish();
        report.event("gate", tool + "_client_history", "succeeded", 0, 0,
                "client tool entry point;2 blocks;Compare;Undo;Redo;Save;Restore");
    }

    private void reopened(ClientGameTestContext context, TestSingleplayerContext world,
            LumiBehaviorReport report) throws IOException {
        new LumiUiTestDriver(context).completeOnboardingIfShown();
        var checks = new LumiBehaviorChecks(context, world, report);
        checks.assertSnapshot("tools_reopened", area, expected);
        if (loaded("axiom")) assertBiomes(world);
        checks.finish();
        report.event("gate", "builder_tools_reopen", "succeeded", 0, 0, "exact world state");
    }

    private static boolean loaded(String mod) {
        return FabricLoader.getInstance().isModLoaded(mod);
    }

    private void exerciseBiomes(ClientGameTestContext context, TestSingleplayerContext world,
            LumiBehaviorReport report) {
        var server = world.getServer();
        expectedDesert = server.computeOnServer(minecraft -> !minecraft.getPlayerList().getPlayers().getFirst()
                .level().getNoiseBiome(origin.getX() >> 2, origin.getY() >> 2, origin.getZ() >> 2).is(net.minecraft.world.level.biome.Biomes.DESERT));
        var before = server.computeOnServer(minecraft -> LumiMod.serverRuntime()
                .find(minecraft.getPlayerList().getPlayers().getFirst().level()).orElseThrow().mutations().builderSnapshot());
        var freeze = server.computeOnServer(minecraft -> LumiMod.serverRuntime()
                .find(minecraft.getPlayerList().getPlayers().getFirst().level()).orElseThrow().freeze().acquire());
        try {
            context.runOnClient(client -> AxiomClientEdits.applyBiome(origin, expectedDesert));
            context.waitTicks(10);
            boolean changed = server.computeOnServer(minecraft -> minecraft.getPlayerList().getPlayers().getFirst()
                    .level().getNoiseBiome(origin.getX() >> 2, origin.getY() >> 2, origin.getZ() >> 2).is(expectedDesert ? net.minecraft.world.level.biome.Biomes.DESERT
                            : net.minecraft.world.level.biome.Biomes.PLAINS));
            if (changed) throw new AssertionError("Integrated Axiom biome dispatcher bypassed the freeze");
        } finally {
            server.runOnServer(minecraft -> freeze.release());
        }
        context.runOnClient(client -> AxiomClientEdits.applyBiome(origin, expectedDesert));
        for (int tick = 0; ; tick++) {
            if (server.computeOnServer(minecraft -> minecraft.getPlayerList().getPlayers().getFirst()
                    .level().getNoiseBiome(origin.getX() >> 2, origin.getY() >> 2, origin.getZ() >> 2).is(expectedDesert ? net.minecraft.world.level.biome.Biomes.DESERT
                            : net.minecraft.world.level.biome.Biomes.PLAINS))) break;
            if (tick == 200) throw new AssertionError("Integrated Axiom biome edit did not reach the server");
            context.waitTick();
        }
        var after = server.computeOnServer(minecraft -> LumiMod.serverRuntime()
                .find(minecraft.getPlayerList().getPlayers().getFirst().level()).orElseThrow().mutations().builderSnapshot());
        if (!before.equals(after)) throw new AssertionError("Integrated biome-only edit entered Lumi history");
        report.event("gate", "axiom_integrated_biomes", "succeeded", 0, 0, "freeze guarded; excluded from history");
    }

    private void assertBiomes(TestSingleplayerContext world) {
        boolean matches = world.getServer().computeOnServer(minecraft -> minecraft.getPlayerList().getPlayers().getFirst()
                .level().getNoiseBiome(origin.getX() >> 2, origin.getY() >> 2, origin.getZ() >> 2).is(expectedDesert ? net.minecraft.world.level.biome.Biomes.DESERT
                        : net.minecraft.world.level.biome.Biomes.PLAINS));
        if (!matches) throw new AssertionError("Block/entity Restore or reopen changed the current Axiom biome");
    }

    private static String coordinates(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    /** Keeps Axiom linkage out of a WorldEdit-only JVM. */
    private static final class AxiomClientEdits {
        private static void applyBiome(BlockPos position, boolean desert) {
            var buffer = new com.moulberry.axiom.world_modification.BiomeBuffer();
            buffer.set(position.getX() >> 2, position.getY() >> 2, position.getZ() >> 2,
                    desert ? net.minecraft.world.level.biome.Biomes.DESERT : net.minecraft.world.level.biome.Biomes.PLAINS);
            com.moulberry.axiom.world_modification.Dispatcher.apply(buffer, false);
        }

        private static void apply(BlockPos position) {
            var buffer = new com.moulberry.axiom.world_modification.BlockBuffer();
            for (int x = 0; x < 2; x++) buffer.set(position.getX() + x,
                    position.getY(), position.getZ(), Blocks.GOLD_BLOCK.defaultBlockState());
            com.moulberry.axiom.world_modification.Dispatcher.applyBlockBuffer(buffer, false);
        }
    }
}
