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
    @GameTest(maxTicks = 2500)
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

    private static FabricDimensionRuntime runtime(GameTestHelper helper) {
        return LumiMod.serverRuntime().find(helper.getLevel())
                .orElseThrow(() -> helper.assertionException("Lumi runtime is not loaded"));
    }

    private static void requireIdle(GameTestHelper helper, FabricDimensionRuntime runtime) {
        helper.assertFalse(runtime.operations().hasActiveOperation(),
                "Lumi operation is still active");
    }
}
