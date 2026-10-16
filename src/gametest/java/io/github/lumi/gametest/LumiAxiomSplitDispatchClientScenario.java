package io.github.lumi.gametest;

import com.moulberry.axiom.world_modification.BlockBuffer;
import com.moulberry.axiom.world_modification.DispatchTask;
import io.github.lumi.LumiMod;
import io.github.lumi.client.integration.AxiomClientWorkBarrier;
import io.github.lumi.domain.model.BlockBox;
import java.io.IOException;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Drives a native multi-packet task one section at a time through the real client transport. */
@SuppressWarnings("UnstableApiUsage")
final class LumiAxiomSplitDispatchClientScenario {
    void run(ClientGameTestContext context, TestSingleplayerContext world, LumiBehaviorReport report,
            BlockPos origin, List<BlockBox> area) throws IOException {
        var server = world.getServer();
        var operations = new LumiBehaviorOperations(context, server, report);
        var checks = new LumiBehaviorChecks(context, world, report);
        context.waitFor(client -> !AxiomClientWorkBarrier.isPaused());
        var before = checks.snapshot("axiom_split_before", area);
        var positions = List.of(origin.offset(8, 1, 0), origin.offset(24, 1, 0), origin.offset(40, 1, 0));
        DispatchTask task = context.computeOnClient(client -> {
            var buffer = new BlockBuffer();
            for (BlockPos position : positions) buffer.set(position.getX(), position.getY(), position.getZ(), Blocks.GOLD_BLOCK.defaultBlockState());
            if (buffer.getSectionCount() != 3) throw new AssertionError("Split fixture needs three native sections");
            return DispatchTask.blockOrBiome(client.level.dimension(), buffer);
        });
        for (int packet = 1; packet <= 3; packet++) {
            context.runOnClient(client -> task.tick(1));
            int expected = packet;
            for (int tick = 0; ; tick++) {
                boolean arrived = server.computeOnServer(minecraft -> {
                    var player = minecraft.getPlayerList().getPlayers().getFirst();
                    return positions.stream().filter(position -> player.level().getBlockState(position).is(Blocks.GOLD_BLOCK)).count() == expected;
                });
                if (arrived) break;
                if (tick == 200) throw new AssertionError("Native split packet did not arrive: " + packet);
                context.waitTick();
            }
            if (packet < 3) server.runOnServer(minecraft -> {
                var player = minecraft.getPlayerList().getPlayers().getFirst();
                var journal = LumiMod.serverRuntime().find(player.level()).orElseThrow().liveActions();
                try {
                    journal.prepareUndo(player.getUUID());
                    throw new AssertionError("Incomplete native dispatch was offered for Undo");
                } catch (IllegalStateException waiting) {
                    if (!waiting.getMessage().contains("still arriving")) throw waiting;
                }
            });
        }
        if (!context.computeOnClient(client -> task.isDone())) throw new AssertionError("Native dispatch did not finish");
        for (int tick = 0; ; tick++) {
            boolean complete = server.computeOnServer(minecraft -> {
                var player = minecraft.getPlayerList().getPlayers().getFirst();
                try {
                    return LumiMod.serverRuntime().find(player.level()).orElseThrow().liveActions()
                            .prepareUndo(player.getUUID()).orElseThrow().expected().size() == 3;
                } catch (IllegalStateException waiting) {
                    if (!waiting.getMessage().contains("still arriving")) throw waiting;
                    return false;
                }
            });
            if (complete) break;
            if (tick == 200) throw new AssertionError("Native dispatch completion was not observed");
            context.waitTick();
        }
        operations.awaitDurability("axiom_split");
        var after = checks.snapshot("axiom_split_after", area);
        operations.undo("axiom_split");
        checks.assertSnapshot("axiom_split_undo", area, before);
        operations.redo("axiom_split");
        checks.assertSnapshot("axiom_split_redo", area, after);
        operations.awaitDurability("axiom_split_redo");
        report.event("gate", "axiom_split_dispatch", "succeeded", 0, 0,
                "three native serialized section packets; no partial Undo; one exact Undo/Redo; integrated transport");
    }
}
