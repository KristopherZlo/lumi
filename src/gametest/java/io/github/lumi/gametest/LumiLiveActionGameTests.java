package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Integrated gates for exact session-only world actions. */
public final class LumiLiveActionGameTests {
    @GameTest(maxTicks = 200)
    public void directBlockUndoRedoIsExact(GameTestHelper helper) {
        FabricDimensionRuntime runtime = LumiMod.serverRuntime().find(helper.getLevel())
                .orElseThrow(() -> helper.assertionException("Lumi runtime is not loaded"));
        UUID player = UUID.randomUUID();
        BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlock(position, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player)) {
            helper.getLevel().setBlock(
                    position, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.assertBlockState(new BlockPos(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState());

        runtime.startLiveAction(player, LiveActionJournal.Direction.UNDO, ignored -> { });
        helper.startSequence()
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertBlockState(
                        new BlockPos(1, 1, 1), Blocks.STONE.defaultBlockState()))
                .thenExecute(() -> runtime.startLiveAction(
                        player, LiveActionJournal.Direction.REDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertBlockState(
                        new BlockPos(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState()))
                .thenSucceed();
    }

    private static void requireIdle(GameTestHelper helper, FabricDimensionRuntime runtime) {
        helper.assertFalse(runtime.operations().hasActiveOperation(),
                "Lumi operation is still active");
    }
}
