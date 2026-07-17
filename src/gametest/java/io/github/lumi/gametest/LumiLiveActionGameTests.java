package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;

/** Integrated gates for exact session-only world actions. */
public final class LumiLiveActionGameTests {
    @GameTest(maxTicks = 2000)
    public void redoneDynamicEntityRemainsUndoableAfterTicking(GameTestHelper helper) {
        FabricDimensionRuntime runtime = LumiMod.serverRuntime().find(helper.getLevel())
                .orElseThrow(() -> helper.assertionException("Lumi runtime is not loaded"));
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        AtomicReference<UUID> itemId = new AtomicReference<>();
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
                    ItemEntity item = new ItemEntity(
                            helper.getLevel(), position.getX() + 0.5,
                            position.getY(), position.getZ() + 0.5,
                            new ItemStack(Items.DIAMOND));
                    itemId.set(item.getUUID());
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), player)) {
                        helper.getLevel().addFreshEntity(item);
                    }
                    runtime.startLiveAction(player, LiveActionJournal.Direction.UNDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertValueEqual(
                        MutationTerminalState.SUCCEEDED, terminal.get(),
                        "Initial item Undo must succeed"))
                .thenExecute(() -> helper.assertTrue(
                        helper.getLevel().getEntity(itemId.get()) == null,
                        "Initial Undo left the item behind"))
                .thenExecute(() -> {
                    terminal.set(null);
                    runtime.startLiveAction(player, LiveActionJournal.Direction.REDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertValueEqual(
                        MutationTerminalState.SUCCEEDED, terminal.get(),
                        "Item Redo must succeed"))
                .thenIdle(5)
                .thenExecute(() -> {
                    terminal.set(null);
                    runtime.startLiveAction(player, LiveActionJournal.Direction.UNDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertValueEqual(
                        MutationTerminalState.SUCCEEDED, terminal.get(),
                        "Ticked Redo item must remain Undoable"))
                .thenExecute(() -> helper.assertTrue(
                        helper.getLevel().getEntity(itemId.get()) == null,
                        "Repeated Undo left the item behind"))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void undoneDynamicEntityRemainsRedoableAfterTicking(GameTestHelper helper) {
        FabricDimensionRuntime runtime = LumiMod.serverRuntime().find(helper.getLevel())
                .orElseThrow(() -> helper.assertionException("Lumi runtime is not loaded"));
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        AtomicReference<UUID> itemId = new AtomicReference<>();
        AtomicReference<Integer> restoredAge = new AtomicReference<>();
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
                    ItemEntity item = new ItemEntity(
                            helper.getLevel(), position.getX() + 0.5,
                            position.getY(), position.getZ() + 0.5,
                            new ItemStack(Items.EMERALD));
                    helper.assertTrue(helper.getLevel().addFreshEntity(item),
                            "Control item could not be spawned");
                    itemId.set(item.getUUID());
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), player)) {
                        item.discard();
                    }
                    runtime.startLiveAction(player, LiveActionJournal.Direction.UNDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertValueEqual(
                            MutationTerminalState.SUCCEEDED, terminal.get(),
                            "Initial item Undo must succeed");
                    ItemEntity restored = (ItemEntity) helper.getLevel()
                            .getEntity(itemId.get());
                    helper.assertTrue(restored != null,
                            "Undo did not restore the removed item");
                    restoredAge.set(restored.getAge());
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    ItemEntity ticked = (ItemEntity) helper.getLevel()
                            .getEntity(itemId.get());
                    helper.assertTrue(ticked != null
                                    && ticked.getAge() > restoredAge.get(),
                            "Restored item did not tick before Redo");
                    terminal.set(null);
                    runtime.startLiveAction(player, LiveActionJournal.Direction.REDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertValueEqual(
                        MutationTerminalState.SUCCEEDED, terminal.get(),
                        "Ticked Undo item must remain Redoable"))
                .thenExecute(() -> helper.assertTrue(
                        helper.getLevel().getEntity(itemId.get()) == null,
                        "Redo left the removed item behind"))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 2000)
    public void derivedLeafWaveRemainsOwnedByRootAction(GameTestHelper helper) {
        FabricDimensionRuntime runtime = LumiMod.serverRuntime().find(helper.getLevel())
                .orElseThrow(() -> helper.assertionException("Lumi runtime is not loaded"));
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        BlockPos logRelative = new BlockPos(1, 1, 2);
        List<BlockPos> leaves = List.of(
                new BlockPos(2, 1, 2), new BlockPos(3, 1, 2),
                new BlockPos(4, 1, 2), new BlockPos(5, 1, 2));
        BlockPos log = helper.absolutePos(logRelative);
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    helper.setBlock(logRelative, Blocks.OAK_LOG);
                    for (int index = 0; index < leaves.size(); index++) {
                        helper.setBlock(leaves.get(index),
                                Blocks.OAK_LEAVES.defaultBlockState()
                                        .setValue(LeavesBlock.DISTANCE, index + 1));
                    }
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), player)) {
                        helper.getLevel().setBlock(
                                log, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    for (BlockPos leaf : leaves) {
                        helper.assertValueEqual(7,
                                helper.getBlockState(leaf).getValue(LeavesBlock.DISTANCE),
                                "Leaf-distance wave did not settle");
                    }
                    runtime.startLiveAction(player, LiveActionJournal.Direction.UNDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertValueEqual(
                        MutationTerminalState.SUCCEEDED, terminal.get(),
                        "Derived leaf wave must Undo atomically"))
                .thenExecute(() -> helper.assertBlockState(
                        logRelative, Blocks.OAK_LOG.defaultBlockState()))
                .thenExecute(() -> {
                    for (int index = 0; index < leaves.size(); index++) {
                        helper.assertBlockState(leaves.get(index),
                                Blocks.OAK_LEAVES.defaultBlockState()
                                        .setValue(LeavesBlock.DISTANCE, index + 1));
                    }
                })
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

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
