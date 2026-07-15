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
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

/** Exact active-carrier and completed-result gates for piston actions. */
public final class LumiPistonGameTests {
    private static final BlockPos PISTON = new BlockPos(2, 2, 3);
    private static final BlockPos TRIGGER = new BlockPos(2, 2, 2);
    private static final BlockPos PAYLOAD = new BlockPos(3, 2, 3);
    private static final BlockPos DESTINATION = new BlockPos(4, 2, 3);

    @GameTest(maxTicks = 2500)
    public void midMoveUndoFinalizesOwnedPiston(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        AtomicReference<Map<BlockPos, BlockState>> baseline = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> preparePiston(helper))
                .thenIdle(5)
                .thenExecute(() -> {
                    baseline.set(snapshot(helper));
                    power(helper, runtime, player);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        hasMovingPiston(helper), "Piston carrier did not start"))
                .thenExecute(() -> runtime.startLiveAction(
                        player, LiveActionJournal.Direction.UNDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> assertSnapshot(helper, baseline.get()))
                .thenIdle(20)
                .thenExecute(() -> assertSnapshot(helper, baseline.get()))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 2500)
    public void completedMoveIsExactAndConflictIsAtomic(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        AtomicReference<Map<BlockPos, BlockState>> baseline = new AtomicReference<>();
        AtomicReference<Map<BlockPos, BlockState>> moved = new AtomicReference<>();
        AtomicReference<Map<BlockPos, BlockState>> overlapped = new AtomicReference<>();
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> preparePiston(helper))
                .thenIdle(5)
                .thenExecute(() -> {
                    baseline.set(snapshot(helper));
                    power(helper, runtime, firstPlayer);
                })
                .thenWaitUntil(() -> helper.assertBlockState(
                        DESTINATION, Blocks.STONE.defaultBlockState()))
                .thenIdle(5)
                .thenExecute(() -> {
                    moved.set(snapshot(helper));
                    helper.assertFalse(moved.get().equals(baseline.get()),
                            "Owned piston did not change the test volume");
                    runtime.startLiveAction(
                            firstPlayer, LiveActionJournal.Direction.UNDO, ignored -> { });
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> assertSnapshot(helper, baseline.get()))
                .thenExecute(() -> runtime.startLiveAction(
                        firstPlayer, LiveActionJournal.Direction.REDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> assertSnapshot(helper, moved.get()))
                .thenIdle(10)
                .thenExecute(() -> assertSnapshot(helper, moved.get()))
                .thenExecute(() -> {
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), secondPlayer)) {
                        helper.getLevel().setBlock(helper.absolutePos(DESTINATION),
                                Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    overlapped.set(snapshot(helper));
                    runtime.startLiveAction(firstPlayer, LiveActionJournal.Direction.UNDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertValueEqual(
                        MutationTerminalState.FAILED, terminal.get(),
                        "Overlapping piston Undo must fail"))
                .thenExecute(() -> assertSnapshot(helper, overlapped.get()))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    private static void preparePiston(GameTestHelper helper) {
        helper.setBlock(PISTON, Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST));
        helper.setBlock(PAYLOAD, Blocks.STONE);
    }

    private static void power(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            UUID player) {
        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player)) {
            helper.getLevel().setBlock(helper.absolutePos(TRIGGER),
                    Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static boolean hasMovingPiston(GameTestHelper helper) {
        return helper.getBlockState(PAYLOAD).is(Blocks.MOVING_PISTON)
                || helper.getBlockState(DESTINATION).is(Blocks.MOVING_PISTON);
    }

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper helper) {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        for (int x = 1; x <= 5; x++) for (int y = 1; y <= 3; y++) {
            for (int z = 1; z <= 4; z++) {
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
