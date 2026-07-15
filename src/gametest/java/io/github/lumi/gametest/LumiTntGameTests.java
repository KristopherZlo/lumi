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
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Exact active-carrier and completed-result gates for TNT actions. */
public final class LumiTntGameTests {
    @GameTest(maxTicks = 2500)
    public void midFuseUndoCancelsOwnedTnt(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        BlockPos tnt = new BlockPos(3, 2, 3);

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    helper.setBlock(tnt.below(), Blocks.OBSIDIAN);
                    helper.setBlock(tnt, Blocks.TNT);
                    prime(helper, runtime, player, tnt, 200);
                    helper.assertEntityPresent(EntityType.TNT);
                    runtime.startLiveAction(
                            player, LiveActionJournal.Direction.UNDO, ignored -> { });
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertBlockState(
                        tnt, Blocks.TNT.defaultBlockState()))
                .thenExecute(() -> helper.assertEntityNotPresent(EntityType.TNT))
                .thenIdle(220)
                .thenExecute(() -> helper.assertBlockState(
                        tnt, Blocks.TNT.defaultBlockState()))
                .thenExecute(() -> helper.assertEntityNotPresent(EntityType.TNT))
                .thenExecute(() -> runtime.startLiveAction(
                        player, LiveActionJournal.Direction.REDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertBlockState(
                        tnt, Blocks.AIR.defaultBlockState()))
                .thenExecute(() -> helper.assertEntityNotPresent(EntityType.TNT))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 2500)
    public void completedExplosionIsExactAndConflictIsAtomic(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        BlockPos tnt = new BlockPos(3, 2, 3);
        AtomicReference<Map<BlockPos, BlockState>> baseline = new AtomicReference<>();
        AtomicReference<Map<BlockPos, BlockState>> exploded = new AtomicReference<>();
        AtomicReference<Map<BlockPos, BlockState>> overlapped = new AtomicReference<>();
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    fillStone(helper);
                    helper.setBlock(tnt, Blocks.TNT);
                    baseline.set(snapshot(helper));
                    prime(helper, runtime, firstPlayer, tnt, 3);
                    helper.assertEntityPresent(EntityType.TNT);
                })
                .thenWaitUntil(() -> helper.assertEntityNotPresent(EntityType.TNT))
                .thenExecute(() -> {
                    exploded.set(snapshot(helper));
                    helper.assertFalse(exploded.get().equals(baseline.get()),
                            "Owned TNT did not change the test volume");
                    runtime.startLiveAction(
                            firstPlayer, LiveActionJournal.Direction.UNDO, ignored -> { });
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> assertSnapshot(helper, baseline.get()))
                .thenExecute(() -> helper.assertEntityNotPresent(EntityType.ITEM))
                .thenExecute(() -> runtime.startLiveAction(
                        firstPlayer, LiveActionJournal.Direction.REDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> assertSnapshot(helper, exploded.get()))
                .thenExecute(() -> {
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), secondPlayer)) {
                        helper.getLevel().setBlock(helper.absolutePos(tnt),
                                Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    overlapped.set(snapshot(helper));
                    runtime.startLiveAction(firstPlayer, LiveActionJournal.Direction.UNDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertValueEqual(
                        MutationTerminalState.FAILED, terminal.get(),
                        "Overlapping TNT Undo must fail"))
                .thenExecute(() -> assertSnapshot(helper, overlapped.get()))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    private static void prime(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            UUID player,
            BlockPos relative,
            int fuse) {
        BlockPos position = helper.absolutePos(relative);
        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player)) {
            if (!TntBlock.prime(helper.getLevel(), position)) {
                throw helper.assertionException("TNT could not be primed");
            }
            helper.getLevel().setBlock(
                    position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        PrimedTnt carrier = helper.findOneEntity(EntityType.TNT);
        carrier.setFuse(fuse);
    }

    private static void fillStone(GameTestHelper helper) {
        for (int x = 1; x <= 5; x++) for (int y = 1; y <= 3; y++) {
            for (int z = 1; z <= 5; z++) helper.setBlock(x, y, z, Blocks.STONE);
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
