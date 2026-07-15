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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Exact active-carrier and landed-result gates for gravity actions. */
public final class LumiFallingBlockGameTests {
    private static final BlockPos SUPPORT = new BlockPos(3, 5, 3);
    private static final BlockPos SOURCE = SUPPORT.above();
    private static final BlockPos LANDING = new BlockPos(3, 2, 3);

    @GameTest(maxTicks = 2500)
    public void midFallUndoCancelsOwnedCarrier(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> prepareColumn(helper))
                .thenIdle(5)
                .thenExecute(() -> removeSupport(helper, runtime, player))
                .thenWaitUntil(() -> helper.assertEntityPresent(EntityType.FALLING_BLOCK))
                .thenExecute(() -> runtime.startLiveAction(
                        player, LiveActionJournal.Direction.UNDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertBlockState(
                        SUPPORT, Blocks.STONE.defaultBlockState()))
                .thenExecute(() -> helper.assertBlockState(
                        SOURCE, Blocks.SAND.defaultBlockState()))
                .thenExecute(() -> helper.assertBlockState(
                        LANDING, Blocks.AIR.defaultBlockState()))
                .thenExecute(() -> helper.assertEntityNotPresent(EntityType.FALLING_BLOCK))
                .thenIdle(100)
                .thenExecute(() -> helper.assertBlockState(
                        SOURCE, Blocks.SAND.defaultBlockState()))
                .thenExecute(() -> helper.assertEntityNotPresent(EntityType.FALLING_BLOCK))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 2500)
    public void completedFallIsExactAndConflictIsAtomic(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        AtomicReference<Map<BlockPos, BlockState>> baseline = new AtomicReference<>();
        AtomicReference<Map<BlockPos, BlockState>> landed = new AtomicReference<>();
        AtomicReference<Map<BlockPos, BlockState>> overlapped = new AtomicReference<>();
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> prepareColumn(helper))
                .thenIdle(5)
                .thenExecute(() -> {
                    baseline.set(snapshot(helper));
                    removeSupport(helper, runtime, firstPlayer);
                })
                .thenWaitUntil(() -> helper.assertEntityPresent(EntityType.FALLING_BLOCK))
                .thenWaitUntil(() -> helper.assertEntityNotPresent(EntityType.FALLING_BLOCK))
                .thenExecute(() -> {
                    landed.set(snapshot(helper));
                    helper.assertFalse(landed.get().equals(baseline.get()),
                            "Owned falling block did not change the test volume");
                    helper.assertBlockState(LANDING, Blocks.SAND.defaultBlockState());
                    runtime.startLiveAction(
                            firstPlayer, LiveActionJournal.Direction.UNDO, ignored -> { });
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> assertSnapshot(helper, baseline.get()))
                .thenExecute(() -> runtime.startLiveAction(
                        firstPlayer, LiveActionJournal.Direction.REDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> assertSnapshot(helper, landed.get()))
                .thenExecute(() -> {
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), secondPlayer)) {
                        helper.getLevel().setBlock(helper.absolutePos(LANDING),
                                Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    overlapped.set(snapshot(helper));
                    runtime.startLiveAction(firstPlayer, LiveActionJournal.Direction.UNDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertValueEqual(
                        MutationTerminalState.FAILED, terminal.get(),
                        "Overlapping falling-block Undo must fail"))
                .thenExecute(() -> assertSnapshot(helper, overlapped.get()))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    private static void prepareColumn(GameTestHelper helper) {
        helper.setBlock(LANDING.below(), Blocks.OBSIDIAN);
        helper.setBlock(SUPPORT, Blocks.STONE);
        helper.setBlock(SOURCE, Blocks.SAND);
    }

    private static void removeSupport(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            UUID player) {
        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player)) {
            helper.getLevel().setBlock(helper.absolutePos(SUPPORT),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper helper) {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        for (int x = 2; x <= 4; x++) for (int y = 1; y <= 6; y++) {
            for (int z = 2; z <= 4; z++) {
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
