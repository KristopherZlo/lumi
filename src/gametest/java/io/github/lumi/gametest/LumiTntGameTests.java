package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.minecraft.world.MinecraftEntityChunkCapture;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Exact active-carrier and completed-result gates for TNT actions. */
public final class LumiTntGameTests {
    @GameTest(maxTicks = 300000)
    public void oneUndoCancelsAllActiveTntActions(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        List<BlockPos> positions = List.of(
                new BlockPos(2, 2, 3),
                new BlockPos(3, 2, 3),
                new BlockPos(4, 2, 3),
                new BlockPos(5, 2, 3));
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    positions.forEach(position -> {
                        helper.setBlock(position.below(), Blocks.OBSIDIAN);
                        helper.setBlock(position, Blocks.TNT);
                        prime(helper, runtime, player, position);
                    });
                    helper.assertEntitiesPresent(EntityType.TNT, positions.size());
                    helper.getEntities(EntityType.TNT).forEach(carrier -> carrier.setFuse(200));
                    runtime.startLiveAction(player, LiveActionJournal.Direction.UNDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED, terminal.get(),
                            "One Undo must cancel the complete active TNT wave");
                    positions.forEach(position -> helper.assertBlockState(
                            position, Blocks.TNT.defaultBlockState()));
                    assertNoActiveEntities(helper, EntityType.TNT);
                })
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void activeTntOutlivesHistoryCountEviction(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        BlockPos tnt = new BlockPos(3, 2, 3);

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    cage(helper, tnt);
                    for (int index = 0; index < 65; index++) {
                        helper.setBlock(tnt, Blocks.TNT);
                        prime(helper, runtime, player, tnt);
                    }
                    helper.assertEntitiesPresent(EntityType.TNT, 65);
                    helper.getEntities(EntityType.TNT).forEach(carrier -> carrier.setFuse(5));
                })
                .thenWaitUntil(() -> helper.assertEntityNotPresent(EntityType.TNT))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void midFuseUndoCancelsOwnedTnt(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        BlockPos tnt = new BlockPos(3, 2, 3);
        AtomicReference<UUID> carrierId = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    helper.setBlock(tnt.below(), Blocks.OBSIDIAN);
                    helper.setBlock(tnt, Blocks.TNT);
                    prime(helper, runtime, player, tnt, 200);
                    helper.assertEntityPresent(EntityType.TNT);
                    carrierId.set(helper.findOneEntity(EntityType.TNT).getUUID());
                    runtime.startLiveAction(
                            player, LiveActionJournal.Direction.UNDO, ignored -> { });
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertBlockState(
                        tnt, Blocks.TNT.defaultBlockState()))
                .thenExecute(() -> assertNoActiveEntities(helper, EntityType.TNT))
                .thenIdle(220)
                .thenExecute(() -> helper.assertBlockState(
                        tnt, Blocks.TNT.defaultBlockState()))
                .thenExecute(() -> assertNoActiveEntities(helper, EntityType.TNT))
                .thenExecute(() -> runtime.startLiveAction(
                        player, LiveActionJournal.Direction.REDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertBlockState(tnt, Blocks.AIR.defaultBlockState());
                    List<PrimedTnt> active = activeEntities(helper, EntityType.TNT);
                    helper.assertValueEqual(1, active.size(),
                            "Redo must restore exactly one active TNT carrier");
                    helper.assertValueEqual(carrierId.get(), active.get(0).getUUID(),
                            "Redo must restore the original TNT carrier UUID");
                    runtime.startLiveAction(
                            player, LiveActionJournal.Direction.UNDO, ignored -> { });
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertBlockState(
                        tnt, Blocks.TNT.defaultBlockState()))
                .thenExecute(() -> assertNoActiveEntities(helper, EntityType.TNT))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void redstoneMidFuseUndoRedoExplosionRemainsUndoable(
            GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        BlockPos tnt = new BlockPos(3, 2, 3);
        BlockPos trigger = tnt.east();
        AtomicReference<Map<BlockPos, BlockState>> baseline = new AtomicReference<>();
        AtomicReference<MutationTerminalState> firstUndoTerminal = new AtomicReference<>();
        AtomicReference<MutationTerminalState> redoTerminal = new AtomicReference<>();
        AtomicReference<MutationTerminalState> finalUndoTerminal = new AtomicReference<>();
        AtomicReference<UUID> carrierId = new AtomicReference<>();
        AtomicReference<PrimedTnt> originalCarrier = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    fillStone(helper);
                    helper.setBlock(trigger, Blocks.AIR);
                    helper.setBlock(tnt, Blocks.TNT);
                    baseline.set(snapshot(helper));
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), player)) {
                        helper.getLevel().setBlock(helper.absolutePos(trigger),
                                Blocks.REDSTONE_BLOCK.defaultBlockState(),
                                Block.UPDATE_ALL);
                    }
                    helper.assertBlockState(trigger,
                            Blocks.REDSTONE_BLOCK.defaultBlockState());
                    helper.assertValueEqual(Blocks.AIR.defaultBlockState(),
                            helper.getBlockState(tnt),
                            "Initial redstone ignition must remove the TNT block");
                    helper.assertEntitiesPresent(EntityType.TNT, 1);
                    PrimedTnt carrier = helper.findOneEntity(EntityType.TNT);
                    originalCarrier.set(carrier);
                    carrierId.set(carrier.getUUID());
                    carrier.setFuse(200);
                    runtime.startLiveAction(player, LiveActionJournal.Direction.UNDO,
                            operation -> firstUndoTerminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> helper.assertTrue(firstUndoTerminal.get() != null,
                        "Mid-fuse Undo is still running"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED,
                            firstUndoTerminal.get(), "Mid-fuse Undo must succeed");
                    assertSnapshot(helper, baseline.get());
                    helper.assertTrue(originalCarrier.get().isRemoved(),
                            "Undo did not mark the original TNT carrier removed");
                    helper.assertValueEqual(Entity.RemovalReason.UNLOADED_WITH_PLAYER,
                            originalCarrier.get().getRemovalReason(),
                            "Undo must remove the carrier through exact replacement");
                    assertNotDurablyCaptured(helper, originalCarrier.get());
                    List<PrimedTnt> active = activeEntities(helper, EntityType.TNT);
                    helper.assertTrue(active.isEmpty(),
                            "Active TNT remained after Undo; original=" + carrierId.get()
                                    + ", active=" + active.stream()
                                            .map(LumiTntGameTests::describeEntity).toList());
                    assertNoActiveEntities(helper, EntityType.ITEM);
                    runtime.startLiveAction(player, LiveActionJournal.Direction.REDO,
                            operation -> redoTerminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> helper.assertTrue(redoTerminal.get() != null,
                        "Redo is still running"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED,
                            redoTerminal.get(), "Redo must succeed");
                    helper.assertValueEqual(Blocks.AIR.defaultBlockState(),
                            helper.getBlockState(tnt),
                            "Redo must restore the ignited TNT block endpoint");
                    List<PrimedTnt> carriers = activeEntities(helper, EntityType.TNT);
                    helper.assertValueEqual(1, carriers.size(),
                            "Redo must restore exactly one TNT carrier");
                    PrimedTnt restored = carriers.get(0);
                    helper.assertValueEqual(carrierId.get(), restored.getUUID(),
                            "Redo did not restore the original TNT carrier UUID");
                    restored.setFuse(3);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        activeEntities(helper, EntityType.TNT).isEmpty(),
                        "Redone TNT is still active"))
                .thenExecute(() -> {
                    helper.assertFalse(snapshot(helper).equals(baseline.get()),
                            "Redone TNT did not change the test volume");
                    runtime.startLiveAction(player, LiveActionJournal.Direction.UNDO,
                            operation -> finalUndoTerminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> helper.assertTrue(finalUndoTerminal.get() != null,
                        "Undo after the redone explosion is still running"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED,
                            finalUndoTerminal.get(),
                            "Undo after the redone explosion must succeed");
                    assertSnapshot(helper, baseline.get());
                    assertNoActiveEntities(helper, EntityType.TNT);
                    assertNoActiveEntities(helper, EntityType.ITEM);
                })
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void olderBlastJoinsNewerActionAndUndoCancelsItsCarrier(
            GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        BlockPos firstTnt = new BlockPos(3, 2, 3);
        BlockPos secondTnt = firstTnt.east();
        BlockPos trigger = secondTnt.east();
        AtomicReference<PrimedTnt> firstCarrier = new AtomicReference<>();
        AtomicReference<PrimedTnt> secondCarrier = new AtomicReference<>();
        AtomicReference<UUID> secondAction = new AtomicReference<>();
        AtomicReference<Map<BlockPos, BlockState>> expectedAfterUndo = new AtomicReference<>();
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    helper.setBlock(firstTnt.below(), Blocks.OBSIDIAN);
                    helper.setBlock(secondTnt.below(), Blocks.OBSIDIAN);
                    helper.setBlock(firstTnt, Blocks.TNT);
                    helper.setBlock(secondTnt, Blocks.TNT);
                    helper.setBlock(trigger, Blocks.AIR);
                    prime(helper, runtime, player, firstTnt, 200);
                    firstCarrier.set(helper.findOneEntity(EntityType.TNT));
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), player)) {
                        secondAction.set(DirectLiveActionContext.current(
                                runtime.liveActions()).orElseThrow());
                        helper.getLevel().setBlock(helper.absolutePos(trigger),
                                Blocks.REDSTONE_BLOCK.defaultBlockState(),
                                Block.UPDATE_ALL);
                    }
                    secondCarrier.set(activeEntities(helper, EntityType.TNT).stream()
                            .filter(carrier -> !carrier.getUUID().equals(
                                    firstCarrier.get().getUUID()))
                            .findFirst()
                            .orElseThrow(() -> helper.assertionException(
                                    "Redstone did not prime the second TNT")));
                    secondCarrier.get().setFuse(200);
                    firstCarrier.get().setFuse(3);
                })
                .thenWaitUntil(() -> {
                    helper.assertBlockState(trigger, Blocks.AIR.defaultBlockState());
                    helper.assertTrue(firstCarrier.get().isRemoved(),
                            "The first TNT has not exploded");
                    helper.assertFalse(secondCarrier.get().isRemoved(),
                            "The newer TNT carrier disappeared");
                })
                .thenExecute(() -> {
                    helper.assertTrue(runtime.liveActions()
                                    .summary(secondAction.get()).delayedReferences() > 0,
                            "The newer action has no active carrier ownership");
                    Map<BlockPos, BlockState> expected = new LinkedHashMap<>(
                            snapshot(helper));
                    expected.put(secondTnt, Blocks.TNT.defaultBlockState());
                    expectedAfterUndo.set(Map.copyOf(expected));
                    runtime.startLiveAction(player, LiveActionJournal.Direction.UNDO,
                            operation -> terminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED, terminal.get(),
                            "The newer action must include the older blast endpoint");
                    helper.assertFalse(runtime.freeze().isFrozen(),
                            "Successful Undo retained the dimension freeze");
                    assertSnapshot(helper, expectedAfterUndo.get());
                    helper.assertTrue(secondCarrier.get().isRemoved(),
                            "Undo did not cancel the newer TNT carrier");
                    assertNoActiveEntities(helper, EntityType.TNT);
                })
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
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

    @GameTest(maxTicks = 300000)
    public void completedExplosionRestoresArmoredStandExactly(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        BlockPos tnt = new BlockPos(3, 2, 3);
        BlockPos standPosition = tnt.south();
        AtomicReference<UUID> standId = new AtomicReference<>();
        AtomicReference<UUID> cartId = new AtomicReference<>();
        AtomicReference<EntityState> standBaseline = new AtomicReference<>();
        AtomicReference<EntityState> cartBaseline = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    for (int x = 1; x <= 5; x++) {
                        for (int z = 1; z <= 5; z++) {
                            helper.setBlock(x, 1, z, Blocks.OBSIDIAN);
                        }
                    }
                    helper.setBlock(tnt, Blocks.TNT);
                    ArmorStand stand = helper.spawn(
                            EntityType.ARMOR_STAND, standPosition);
                    stand.setItemSlot(EquipmentSlot.FEET,
                            new ItemStack(Items.DIAMOND_BOOTS));
                    stand.setItemSlot(EquipmentSlot.LEGS,
                            new ItemStack(Items.DIAMOND_LEGGINGS));
                    stand.setItemSlot(EquipmentSlot.CHEST,
                            new ItemStack(Items.DIAMOND_CHESTPLATE));
                    stand.setItemSlot(EquipmentSlot.HEAD,
                            new ItemStack(Items.DIAMOND_HELMET));
                    standId.set(stand.getUUID());
                    MinecartChest cart = helper.spawn(
                            EntityType.CHEST_MINECART, tnt.west());
                    cart.setItem(0, new ItemStack(Items.DIAMOND, 7));
                    cartId.set(cart.getUUID());
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    Entity stand = helper.getLevel().getEntityInAnyDimension(
                            standId.get());
                    helper.assertTrue(stand instanceof ArmorStand,
                            "Armored stand disappeared before the explosion");
                    helper.assertTrue(stand.onGround(),
                            "Armored stand baseline was captured before landing");
                    standBaseline.set(captureEntity(helper, stand));
                    Entity cart = helper.getLevel().getEntityInAnyDimension(
                            cartId.get());
                    helper.assertTrue(cart instanceof MinecartChest,
                            "Chest minecart disappeared before the explosion");
                    cartBaseline.set(captureEntity(helper, cart));
                    prime(helper, runtime, player, tnt, 3);
                })
                .thenWaitUntil(() -> helper.assertEntityNotPresent(EntityType.TNT))
                .thenWaitUntil(() -> helper.assertEntityNotPresent(
                        EntityType.ARMOR_STAND))
                .thenWaitUntil(() -> helper.assertEntityNotPresent(
                        EntityType.CHEST_MINECART))
                .thenExecute(() -> runtime.startLiveAction(
                        player, LiveActionJournal.Direction.UNDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    Entity restored = helper.getLevel().getEntityInAnyDimension(
                            standId.get());
                    helper.assertTrue(restored instanceof ArmorStand,
                            "Undo did not restore the same armor stand UUID");
                    helper.assertValueEqual(
                            standBaseline.get(), captureEntity(helper, restored),
                            "Undo changed armor stand state or equipment");
                    Entity restoredCart = helper.getLevel().getEntityInAnyDimension(
                            cartId.get());
                    helper.assertTrue(restoredCart instanceof MinecartChest,
                            "Undo did not restore the same chest minecart UUID");
                    helper.assertValueEqual(
                            cartBaseline.get(), captureEntity(helper, restoredCart),
                            "Undo changed chest minecart state or inventory");
                    helper.assertBlockState(tnt, Blocks.TNT.defaultBlockState());
                    helper.assertEntityNotPresent(EntityType.ITEM);
                })
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void completedExplosionDoesNotResurrectActionAddedItem(
            GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        BlockPos tnt = new BlockPos(3, 2, 3);
        AtomicReference<UUID> itemId = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    for (int x = 1; x <= 5; x++) {
                        for (int z = 1; z <= 5; z++) {
                            helper.setBlock(x, 1, z, Blocks.OBSIDIAN);
                        }
                    }
                    helper.setBlock(tnt, Blocks.TNT);
                    BlockPos absolute = helper.absolutePos(tnt);
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), player)) {
                        ItemEntity item = new ItemEntity(
                                helper.getLevel(), absolute.getX() + 0.5,
                                absolute.getY() + 1.0, absolute.getZ() + 0.5,
                                new ItemStack(Items.DIRT));
                        helper.assertTrue(helper.getLevel().addFreshEntity(item),
                                "Action item could not be spawned");
                        itemId.set(item.getUUID());
                        prime(helper, runtime, player, tnt, 3);
                    }
                })
                .thenWaitUntil(() -> helper.assertEntityNotPresent(EntityType.TNT))
                .thenWaitUntil(() -> helper.assertEntityNotPresent(EntityType.ITEM))
                .thenExecute(() -> runtime.startLiveAction(
                        player, LiveActionJournal.Direction.UNDO, ignored -> { }))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertTrue(helper.getLevel().getEntityInAnyDimension(
                                    itemId.get()) == null,
                            "Undo resurrected an item created and destroyed by the action");
                    helper.assertEntityNotPresent(EntityType.ITEM);
                    helper.assertBlockState(tnt, Blocks.TNT.defaultBlockState());
                })
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    private static void prime(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            UUID player,
            BlockPos relative,
            int fuse) {
        prime(helper, runtime, player, relative);
        PrimedTnt carrier = helper.findOneEntity(EntityType.TNT);
        carrier.setFuse(fuse);
    }

    private static void prime(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            UUID player,
            BlockPos relative) {
        BlockPos position = helper.absolutePos(relative);
        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player)) {
            if (!TntBlock.prime(helper.getLevel(), position)) {
                throw helper.assertionException("TNT could not be primed");
            }
            helper.getLevel().setBlock(
                    position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static void cage(GameTestHelper helper, BlockPos center) {
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || y != 0 || z != 0) {
                    helper.setBlock(center.offset(x, y, z), Blocks.OBSIDIAN);
                }
            }
        }
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

    private static EntityState captureEntity(
            GameTestHelper helper, Entity entity) {
        try {
            return new MinecraftEntityChunkCapture()
                    .captureEntity(helper.getLevel(), entity)
                    .orElseThrow(() -> helper.assertionException(
                            "Entity is not durable: %s", entity.getUUID()))
                    .state();
        } catch (IOException failed) {
            throw helper.assertionException(
                    "Cannot capture entity %s: %s", entity.getUUID(), failed);
        }
    }

    private static <T extends Entity> List<T> activeEntities(
            GameTestHelper helper, EntityType<T> type) {
        return helper.getEntities(type).stream()
                .filter(entity -> !entity.isRemoved())
                .toList();
    }

    private static <T extends Entity> void assertNoActiveEntities(
            GameTestHelper helper, EntityType<T> type) {
        List<T> active = activeEntities(helper, type);
        helper.assertTrue(active.isEmpty(),
                "Did not expect an active " + EntityType.getKey(type) + ": "
                        + active.stream().map(LumiTntGameTests::describeEntity).toList());
    }

    private static String describeEntity(Entity entity) {
        return entity.getUUID() + " removed=" + entity.isRemoved()
                + " reason=" + entity.getRemovalReason()
                + " pos=" + entity.position();
    }

    private static void assertNotDurablyCaptured(
            GameTestHelper helper, Entity entity) {
        try {
            helper.assertTrue(new MinecraftEntityChunkCapture()
                            .captureEntity(helper.getLevel(), entity)
                            .isEmpty(),
                    "Removed entity remained in the durable world snapshot: "
                            + entity.getUUID());
        } catch (IOException failed) {
            throw helper.assertionException(
                    "Cannot verify removed entity %s: %s", entity.getUUID(), failed);
        }
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
