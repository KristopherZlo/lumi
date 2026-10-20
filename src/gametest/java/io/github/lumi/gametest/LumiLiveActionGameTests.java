package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.minecraft.world.DimensionFreezeState;
import io.github.lumi.minecraft.world.MinecraftLiveEntityWorldAccess;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
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
    @GameTest(maxTicks = 300000)
    public void unchangedEntityActionDoesNotRetainHistory(GameTestHelper helper) {
        var runtime = LumiMod.serverRuntime().find(helper.getLevel()).orElseThrow();
        UUID lease = UUID.randomUUID();
        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, lease))
                .thenExecute(() -> {
                    BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
                    var item = new ItemEntity(helper.getLevel(), position.getX() + 0.5,
                            position.getY(), position.getZ() + 0.5, new ItemStack(Items.DIAMOND));
                    helper.assertTrue(helper.getLevel().addFreshEntity(item), "Missing native no-op fixture");
                    UUID action;
                    try {
                        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), UUID.randomUUID())) {
                            var pending = runtime.liveEntities().begin(item).orElseThrow();
                            action = pending.action();
                            helper.assertFalse(runtime.liveEntities().finish(pending), "No-op changed native state");
                        }
                        helper.assertTrue(runtime.liveActions().owner(action).isEmpty(),
                                "An unchanged entity action retains history and prepared state");
                        UUID player = UUID.randomUUID();
                        UUID original;
                        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player)) {
                            original = DirectLiveActionContext.current(runtime.liveActions()).orElseThrow();
                            runtime.liveEntities().added(item);
                        }
                        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player)) {
                            var pending = runtime.liveEntities().begin(item).orElseThrow();
                            action = pending.action();
                            helper.assertTrue(runtime.liveEntities().owns(original, item.getUUID()),
                                    "A pending no-op stole the previous entity owner");
                            helper.assertFalse(runtime.liveEntities().finish(pending), "No-op changed owned state");
                        }
                        helper.assertTrue(runtime.liveActions().owner(action).isEmpty(),
                                "An unchanged overlapping action retains history");
                        helper.assertTrue(runtime.liveEntities().owns(original, item.getUUID()),
                                "A completed no-op discarded the previous entity owner");
                        item.discard();
                        runtime.liveEntities().finalizeOwned(original);
                    } catch (IOException failed) {
                        throw new java.io.UncheckedIOException(failed);
                    } finally {
                        item.discard();
                    }
                    LumiGameTestLease.release(lease);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void entityPreparedStatesFollowHistoryOwnership(GameTestHelper helper) {
        var runtime = LumiMod.serverRuntime().find(helper.getLevel()).orElseThrow();
        UUID lease = UUID.randomUUID();
        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, lease))
                .thenExecute(() -> {
                    BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
                    var item = new ItemEntity(helper.getLevel(), position.getX() + 0.5,
                            position.getY(), position.getZ() + 0.5, new ItemStack(Items.DIAMOND));
                    helper.assertTrue(helper.getLevel().addFreshEntity(item), "Missing native retention fixture");
                    var readOnly = new MinecraftLiveEntityWorldAccess(helper.getLevel(), new DimensionFreezeState());
                    try {
                        for (int index = 0; index < 16; index++) {
                            item.setYRot(index);
                            helper.assertTrue(readOnly.read(item.getUUID()).isPresent(), "Fixture entity is not loaded");
                        }
                        UUID player = UUID.randomUUID();
                        for (int index = 0; index < 16; index++) {
                            try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player)) {
                                var pending = runtime.liveEntities().begin(item).orElseThrow();
                                item.setYRot(index + 32);
                                runtime.liveEntities().finish(pending);
                            }
                        }
                        runtime.liveEntities().finalizeOwned();
                        runtime.liveActions().clear();
                        var field = FabricDimensionRuntime.class.getDeclaredField("liveEntityWorld");
                        field.setAccessible(true);
                        long reads = preparedEntityCount(readOnly, item.getUUID());
                        long discarded = preparedEntityCount(field.get(runtime), item.getUUID());
                        helper.assertTrue(reads == 0 && discarded == 0,
                                "Unowned native entity cache entries: reads=" + reads + ", discarded=" + discarded);
                    } catch (IOException failed) {
                        throw new java.io.UncheckedIOException(failed);
                    } catch (ReflectiveOperationException failed) {
                        throw new IllegalStateException("Cannot inspect prepared entity retention", failed);
                    } finally {
                        item.discard();
                    }
                    LumiGameTestLease.release(lease);
                })
                .thenSucceed();
    }

    private static long preparedEntityCount(Object world, UUID id) throws ReflectiveOperationException {
        var field = MinecraftLiveEntityWorldAccess.class.getDeclaredField("prepared");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(world)).keySet().stream().map(EntityState.class::cast)
                .filter(state -> state.id().equals(id)).count();
    }

    @GameTest(maxTicks = 300000)
    public void untrackedEntityAdditionPreservesOriginDataVersion(GameTestHelper helper) {
        var level = helper.getLevel();
        var runtime = LumiMod.serverRuntime().find(level).orElseThrow();
        UUID test = UUID.randomUUID();
        var key = new io.github.lumi.domain.model.EntityChunkKey(4096, 4096);
        var boundary = new AtomicReference<io.github.lumi.domain.model.WorkingIndexSnapshot>();
        var spawned = new AtomicReference<Pig>();
        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    level.getChunk(key.chunkX(), key.chunkZ());
                    runtime.entityChunkUnloaded(new net.minecraft.world.level.ChunkPos(key.chunkX(), key.chunkZ()));
                    Pig pig = EntityType.PIG.create(level, EntitySpawnReason.COMMAND);
                    helper.assertTrue(pig != null, "Cannot create the origin control entity");
                    pig.setNoAi(true);
                    pig.setPersistenceRequired();
                    pig.setPos(key.chunkX() * 16 + 0.5, 100, key.chunkZ() * 16 + 0.5);
                    spawned.set(pig);
                    helper.assertTrue(level.addFreshEntity(pig), "Cannot add the origin control entity");
                    boundary.set(runtime.mutations().snapshot());
                })
                .thenWaitUntil(() -> helper.assertTrue(runtime.mutations().isDurable(boundary.get()),
                        "The added entity's original state is not durable"))
                .thenExecute(() -> {
                    try {
                        var origin = new io.github.lumi.storage.repository.OriginStore(runtime.repository())
                                .read(key).orElseThrow();
                        var before = new io.github.lumi.storage.repository.WorldObjectRepository(runtime.repository())
                                .readEntities(origin);
                        helper.assertTrue(before.entities().isEmpty(), "The added entity leaked into its original state");
                        helper.assertValueEqual(net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(),
                                before.dataVersion().value(), "The entity origin lost the running game's data version");
                    } catch (IOException failed) {
                        throw new AssertionError("Cannot read the added entity's durable origin", failed);
                    } finally {
                        spawned.get().discard();
                        LumiGameTestLease.release(test);
                    }
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void oneDamageRootRestoresEveryKilledMob(GameTestHelper helper) {
        FabricDimensionRuntime runtime = LumiMod.serverRuntime().find(helper.getLevel())
                .orElseThrow(() -> helper.assertionException("Lumi runtime is not loaded"));
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        List<Pig> pigs = new ArrayList<>();
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    for (int index = 0; index < 3; index++) {
                        Pig pig = EntityType.PIG.create(
                                helper.getLevel(), EntitySpawnReason.COMMAND);
                        helper.assertTrue(pig != null, "Control pig could not be created");
                        BlockPos position = helper.absolutePos(
                                new BlockPos(index + 1, 2, 2));
                        pig.setPos(position.getX() + 0.5, position.getY(),
                                position.getZ() + 0.5);
                        pig.setHealth(1.0F);
                        helper.assertTrue(helper.getLevel().addFreshEntity(pig),
                                "Control pig could not be spawned");
                        pigs.add(pig);
                    }
                    killTogether(runtime, player, pigs);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        pigs.stream().allMatch(Pig::isRemoved),
                        "Killed pigs have not finished their death animation"))
                .thenExecute(() -> runtime.startLiveAction(
                        player, LiveActionJournal.Direction.UNDO,
                        operation -> terminal.set(operation.terminalState())))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertValueEqual(
                        MutationTerminalState.SUCCEEDED, terminal.get(),
                        "Multi-kill Undo must succeed"))
                .thenExecute(() -> helper.assertTrue(
                        pigs.stream().allMatch(pig ->
                                helper.getLevel().getEntity(pig.getUUID()) instanceof Pig),
                        "Undo did not restore every mob killed by one damage root"))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
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
                    try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player)) {
                        var pending = runtime.liveEntities().begin(item).orElseThrow();
                        item.setYRot(90);
                        runtime.liveEntities().finish(pending);
                    } catch (IOException failed) {
                        throw new java.io.UncheckedIOException(failed);
                    }
                    runtime.startLiveAction(player, LiveActionJournal.Direction.UNDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED, terminal.get(),
                            "The newer edit of the same item must Undo first");
                    helper.assertTrue(helper.getLevel().getEntity(itemId.get()) != null,
                            "Undoing the newer edit removed the older action's item");
                })
                .thenIdle(5)
                .thenExecute(() -> {
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

    @GameTest(maxTicks = 300000)
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
                                        .setValue(LeavesBlock.DISTANCE, index + 1)
                                        .setValue(LeavesBlock.PERSISTENT, true));
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
                                        .setValue(LeavesBlock.DISTANCE, index + 1)
                                        .setValue(LeavesBlock.PERSISTENT, true));
                    }
                })
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
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

    @GameTest(maxTicks = 300000)
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

    @GameTest(maxTicks = 300000)
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

    private static void killTogether(
            FabricDimensionRuntime runtime, UUID player, List<Pig> pigs) {
        try (var ignored = DirectLiveActionContext.open(
                runtime.liveActions(), player)) {
            var primary = runtime.liveEntities().begin(pigs.getFirst());
            try {
                pigs.forEach(pig -> pig.hurtServer(
                        runtime.level(), runtime.level().damageSources().genericKill(),
                        Float.MAX_VALUE));
            } finally {
                if (primary.isPresent()) {
                    runtime.liveEntities().finish(primary.orElseThrow());
                }
            }
        } catch (IOException failed) {
            throw new AssertionError("Could not capture the multi-kill action", failed);
        }
    }

    private static void requireIdle(GameTestHelper helper, FabricDimensionRuntime runtime) {
        helper.assertFalse(runtime.operations().hasActiveOperation(),
                "Lumi operation is still active");
    }
}
