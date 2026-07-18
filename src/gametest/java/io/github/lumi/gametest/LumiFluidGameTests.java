package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Exact active-flow and settled-result gates for fluid actions. */
public final class LumiFluidGameTests {
    private static final BlockPos SOURCE = new BlockPos(3, 3, 3);
    private static final BlockPos FLOW = new BlockPos(3, 2, 3);

    @GameTest(maxTicks = 300000)
    public void midFlowUndoCancelsOwnedFluidTicks(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        AtomicReference<Map<BlockPos, BlockState>> baseline = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    prepareBasin(helper);
                    baseline.set(snapshot(helper));
                    placeWater(helper, runtime, player);
                })
                .thenWaitUntil(() -> helper.assertFalse(
                        helper.getBlockState(FLOW).getFluidState().isEmpty(),
                        "Owned water tick has not reached the basin"))
                .thenExecute(() -> runtime.startLiveAction(
                        player, LiveActionJournal.Direction.UNDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> assertSnapshot(helper, baseline.get()))
                .thenIdle(100)
                .thenExecute(() -> assertSnapshot(helper, baseline.get()))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void settledFlowIsExactAndConflictIsAtomic(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        AtomicReference<Map<BlockPos, BlockState>> baseline = new AtomicReference<>();
        AtomicReference<Map<BlockPos, BlockState>> flowed = new AtomicReference<>();
        AtomicReference<Map<BlockPos, BlockState>> overlapped = new AtomicReference<>();
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    prepareBasin(helper);
                    baseline.set(snapshot(helper));
                    placeWater(helper, runtime, firstPlayer);
                })
                .thenWaitUntil(() -> helper.assertFalse(
                        helper.getBlockState(FLOW).getFluidState().isEmpty(),
                        "Owned water tick has not reached the basin"))
                .thenIdle(100)
                .thenExecute(() -> {
                    flowed.set(snapshot(helper));
                    helper.assertFalse(flowed.get().equals(baseline.get()),
                            "Owned water did not change the test volume");
                })
                .thenIdle(20)
                .thenExecute(() -> assertSnapshot(helper, flowed.get()))
                .thenExecute(() -> runtime.startLiveAction(
                        firstPlayer, LiveActionJournal.Direction.UNDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> assertSnapshot(helper, baseline.get()))
                .thenExecute(() -> runtime.startLiveAction(
                        firstPlayer, LiveActionJournal.Direction.REDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> assertSnapshot(helper, flowed.get()))
                .thenIdle(20)
                .thenExecute(() -> assertSnapshot(helper, flowed.get()))
                .thenExecute(() -> {
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), secondPlayer)) {
                        helper.getLevel().setBlock(helper.absolutePos(FLOW),
                                Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    overlapped.set(snapshot(helper));
                    runtime.startLiveAction(firstPlayer, LiveActionJournal.Direction.UNDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertValueEqual(
                        MutationTerminalState.FAILED, terminal.get(),
                        "Overlapping fluid Undo must fail"))
                .thenExecute(() -> assertSnapshot(helper, overlapped.get()))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    private static void prepareBasin(GameTestHelper helper) {
        for (int x = 1; x <= 5; x++) for (int z = 1; z <= 5; z++) {
            helper.setBlock(x, 1, z, Blocks.OBSIDIAN);
            for (int y = 2; y <= 3; y++) {
                if (x == 1 || x == 5 || z == 1 || z == 5) {
                    helper.setBlock(x, y, z, Blocks.OBSIDIAN);
                }
            }
        }
    }

    private static void placeWater(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            UUID player) {
        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player)) {
            helper.getLevel().setBlock(helper.absolutePos(SOURCE),
                    Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper helper) {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        for (int x = 1; x <= 5; x++) for (int y = 1; y <= 3; y++) {
            for (int z = 1; z <= 5; z++) {
                BlockPos position = new BlockPos(x, y, z);
                states.put(position, helper.getBlockState(position));
            }
        }
        return Map.copyOf(states);
    }

    private static void assertSnapshot(
            GameTestHelper helper, Map<BlockPos, BlockState> expected) {
        expected.forEach(helper::assertBlockState);
    }

    private static FabricDimensionRuntime runtime(GameTestHelper helper) {
        return LumiMod.serverRuntime().find(helper.getLevel())
                .orElseThrow(() -> helper.assertionException("Lumi runtime is not loaded"));
    }

    private static void requireIdle(GameTestHelper helper, FabricDimensionRuntime runtime) {
        helper.assertFalse(runtime.operations().hasActiveOperation(),
                "Lumi operation is still active");
    }
}
