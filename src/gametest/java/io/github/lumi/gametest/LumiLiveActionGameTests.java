package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;

/** Integrated gates for exact session-only world actions. */
public final class LumiLiveActionGameTests {
    @GameTest(maxTicks = 2000)
    public void directBlockUndoRedoIsExact(GameTestHelper helper) {
        FabricDimensionRuntime runtime = LumiMod.serverRuntime().find(helper.getLevel())
                .orElseThrow(() -> helper.assertionException("Lumi runtime is not loaded"));
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    helper.getLevel().setBlock(
                            position, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), player)) {
                        helper.getLevel().setBlock(position,
                                Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    helper.assertBlockState(relative, Blocks.GOLD_BLOCK.defaultBlockState());
                    runtime.startLiveAction(
                            player, LiveActionJournal.Direction.UNDO, ignored -> { });
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertBlockState(
                        relative, Blocks.STONE.defaultBlockState()))
                .thenExecute(() -> runtime.startLiveAction(
                        player, LiveActionJournal.Direction.REDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertBlockState(
                        relative, Blocks.GOLD_BLOCK.defaultBlockState()))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 2000)
    public void activeFireUndoRedoIsExact(GameTestHelper helper) {
        FabricDimensionRuntime runtime = LumiMod.serverRuntime().find(helper.getLevel())
                .orElseThrow(() -> helper.assertionException("Lumi runtime is not loaded"));
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        BlockPos relative = new BlockPos(2, 1, 2);
        BlockPos position = helper.absolutePos(relative);
        AtomicReference<BlockState> placed = new AtomicReference<>();
        AtomicReference<BlockState> settled = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    prepareFire(helper, relative);
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), player)) {
                        helper.getLevel().setBlock(position,
                                BaseFireBlock.getState(helper.getLevel(), position),
                                Block.UPDATE_ALL);
                    }
                    placed.set(helper.getBlockState(relative));
                })
                .thenWaitUntil(() -> helper.assertFalse(
                        helper.getBlockState(relative).equals(placed.get()),
                        "Owned vanilla fire tick has not changed the fire state"))
                .thenExecute(() -> {
                    settled.set(helper.getBlockState(relative));
                    runtime.startLiveAction(
                            player, LiveActionJournal.Direction.UNDO, ignored -> { });
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertBlockState(
                        relative, Blocks.AIR.defaultBlockState()))
                .thenIdle(50)
                .thenExecute(() -> helper.assertBlockState(
                        relative, Blocks.AIR.defaultBlockState()))
                .thenExecute(() -> runtime.startLiveAction(
                        player, LiveActionJournal.Direction.REDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertBlockState(relative, settled.get()))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 2000)
    public void newerFireOverlapRefusesUndoAtomically(GameTestHelper helper) {
        FabricDimensionRuntime runtime = LumiMod.serverRuntime().find(helper.getLevel())
                .orElseThrow(() -> helper.assertionException("Lumi runtime is not loaded"));
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        BlockPos relative = new BlockPos(4, 1, 4);
        BlockPos position = helper.absolutePos(relative);
        AtomicReference<BlockState> placed = new AtomicReference<>();
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    prepareFire(helper, relative);
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), firstPlayer)) {
                        helper.getLevel().setBlock(position,
                                BaseFireBlock.getState(helper.getLevel(), position),
                                Block.UPDATE_ALL);
                    }
                    placed.set(helper.getBlockState(relative));
                })
                .thenWaitUntil(() -> helper.assertFalse(
                        helper.getBlockState(relative).equals(placed.get()),
                        "Owned vanilla fire tick has not changed the fire state"))
                .thenExecute(() -> {
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), secondPlayer)) {
                        helper.getLevel().setBlock(position,
                                Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    runtime.startLiveAction(firstPlayer, LiveActionJournal.Direction.UNDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertValueEqual(
                        MutationTerminalState.FAILED, terminal.get(),
                        "Overlapping fire Undo must fail"))
                .thenExecute(() -> helper.assertBlockState(
                        relative, Blocks.DIAMOND_BLOCK.defaultBlockState()))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    private static void prepareFire(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative.below(), Blocks.NETHERRACK);
        helper.getLevel().getGameRules().set(
                GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, -1,
                helper.getLevel().getServer());
    }

    private static void requireIdle(GameTestHelper helper, FabricDimensionRuntime runtime) {
        helper.assertFalse(runtime.operations().hasActiveOperation(),
                "Lumi operation is still active");
    }
}
