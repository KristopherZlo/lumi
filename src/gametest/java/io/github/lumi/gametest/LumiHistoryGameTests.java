package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorldObjectGraph;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

/** Integrated durable Save/Restore gates over the production dimension runtime. */
public final class LumiHistoryGameTests {
    private static final CommitAuthor AUTHOR =
            new CommitAuthor(new UUID(0, 7), "History gate");
    private static final TicketType TEST_CHUNK_TICKET =
            new TicketType(Long.MAX_VALUE, TicketType.FLAG_LOADING);

    @GameTest
    public void freezeRejectsOrdinaryEntityLifecycle(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        Entity existing = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(2, 2, 2));
        var lease = runtime.freeze().acquire();
        try {
            Entity added = EntityType.ARMOR_STAND.create(
                    helper.getLevel(), EntitySpawnReason.COMMAND);
            helper.assertFalse(added == null, "Cannot create test entity");
            helper.assertFalse(helper.getLevel().addFreshEntity(added),
                    "Frozen dimension accepted an ordinary entity");
            existing.discard();
            helper.assertFalse(existing.isRemoved(),
                    "Frozen dimension removed an ordinary entity");
        } finally {
            lease.release();
        }
        existing.discard();
        helper.succeed();
    }

    @GameTest(maxTicks = 300000)
    public void saveRestoreAddsAndRemovesDurableEntityExactly(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID lease = UUID.randomUUID();
        AtomicReference<Entity> entity = new AtomicReference<>();
        AtomicReference<CommitId> withEntity = new AtomicReference<>();
        AtomicReference<CommitId> changedEntity = new AtomicReference<>();
        AtomicReference<CommitId> withoutEntity = new AtomicReference<>();
        AtomicReference<UUID> zoneId = new AtomicReference<>();
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();
        AtomicReference<DimensionMutation> current = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> acquireZone(
                        helper, runtime, lease, zoneId,
                        helper.absolutePos(new BlockPos(2, 2, 2))))
                .thenExecute(() -> {
                    MinecartChest cart = helper.spawn(
                            EntityType.CHEST_MINECART, new BlockPos(2, 2, 2));
                    cart.setNoGravity(true);
                    cart.setItem(0, new ItemStack(Items.DIAMOND, 7));
                    entity.set(cart);
                    startSave(
                            helper, runtime, zoneId.get(), "With entity",
                            terminal, current);
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime, current))
                .thenExecute(() -> {
                    requireSucceeded(helper, terminal.get(), "Save with entity");
                    withEntity.set(activeCommit(helper, runtime));
                    ((MinecartChest) entity.get()).setItem(
                            0, new ItemStack(Items.EMERALD, 3));
                    startSave(
                            helper, runtime, zoneId.get(), "Changed entity",
                            terminal, current);
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime, current))
                .thenExecute(() -> {
                    requireSucceeded(helper, terminal.get(), "Save changed entity");
                    changedEntity.set(activeCommit(helper, runtime));
                    startRestore(
                            helper, runtime, withEntity.get(), zoneId.get(),
                            terminal, current);
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime, current))
                .thenExecute(() -> {
                    requireSucceeded(helper, terminal.get(), "Restore with entity");
                    assertMinecart(
                            helper, entity.get().getUUID(),
                            new ItemStack(Items.DIAMOND, 7));
                    helper.assertEntityNotPresent(EntityType.ITEM);
                    startRestore(
                            helper, runtime, changedEntity.get(), zoneId.get(),
                            terminal, current);
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime, current))
                .thenExecute(() -> {
                    requireSucceeded(helper, terminal.get(), "Restore changed entity");
                    MinecartChest cart = assertMinecart(
                            helper, entity.get().getUUID(),
                            new ItemStack(Items.EMERALD, 3));
                    helper.assertEntityNotPresent(EntityType.ITEM);
                    cart.clearContent();
                    cart.discard();
                    startSave(
                            helper, runtime, zoneId.get(), "Without entity",
                            terminal, current);
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime, current))
                .thenExecute(() -> {
                    requireSucceeded(helper, terminal.get(), "Save without entity");
                    withoutEntity.set(activeCommit(helper, runtime));
                    startRestore(
                            helper, runtime, withEntity.get(), zoneId.get(),
                            terminal, current);
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime, current))
                .thenExecute(() -> {
                    requireSucceeded(helper, terminal.get(), "Restore added entity");
                    assertMinecart(
                            helper, entity.get().getUUID(),
                            new ItemStack(Items.DIAMOND, 7));
                    helper.assertEntityNotPresent(EntityType.ITEM);
                    startRestore(
                            helper, runtime, withoutEntity.get(), zoneId.get(),
                            terminal, current);
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime, current))
                .thenExecute(() -> {
                    requireSucceeded(helper, terminal.get(), "Restore removed entity");
                    Entity restored = helper.getLevel().getEntityInAnyDimension(
                            entity.get().getUUID());
                    helper.assertTrue(restored == null || restored.isRemoved(),
                            "Restore left a durable entity absent from the target");
                    helper.assertEntityNotPresent(EntityType.ITEM);
                })
                .thenExecute(() -> LumiGameTestLease.release(lease))
                .thenSucceed();
    }

    private static MinecartChest assertMinecart(
            GameTestHelper helper, UUID id, ItemStack expected) {
        Entity restored = helper.getLevel().getEntityInAnyDimension(id);
        helper.assertTrue(restored instanceof MinecartChest,
                "Restore did not recreate the chest minecart");
        MinecartChest cart = (MinecartChest) restored;
        helper.assertTrue(ItemStack.matches(expected, cart.getItem(0)),
                "Restore changed chest-minecart inventory");
        return cart;
    }

    @GameTest(maxTicks = 300000)
    public void saveRestoreReloadsUnloadedChunks(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        ServerLevel level = helper.getLevel();
        UUID lease = UUID.randomUUID();
        BlockPos target = helper.absolutePos(new BlockPos(642, 2, 2));
        ChunkPos chunk = new ChunkPos(target);
        AtomicReference<UUID> zoneId = new AtomicReference<>();
        AtomicReference<CommitId> gold = new AtomicReference<>();
        AtomicReference<CommitId> diamond = new AtomicReference<>();
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();
        AtomicReference<DimensionMutation> current = new AtomicReference<>();
        AtomicReference<CompletableFuture<?>> chunkLoad = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> acquireZone(
                        helper, runtime, lease, zoneId, target))
                .thenExecute(() -> {
                    chunkLoad.set(loadChunk(level, chunk));
                })
                .thenWaitUntil(() -> requireLoaded(helper, level, chunk, chunkLoad.get()))
                .thenExecute(() -> {
                    level.setBlockAndUpdate(target, Blocks.GOLD_BLOCK.defaultBlockState());
                    releaseChunk(level, chunk);
                })
                .thenWaitUntil(() -> requireUnloaded(helper, level, chunk))
                .thenExecute(() -> startSave(
                        helper, runtime, zoneId.get(), "Unloaded gold",
                        terminal, current))
                .thenWaitUntil(() -> requireIdle(helper, runtime, current))
                .thenExecute(() -> {
                    requireSucceeded(helper, terminal.get(), "Save unloaded gold");
                    gold.set(activeCommit(helper, runtime));
                    assertCommitBlock(
                            helper, runtime, gold.get(), target, "minecraft:gold_block");
                    chunkLoad.set(loadChunk(level, chunk));
                })
                .thenWaitUntil(() -> requireLoaded(helper, level, chunk, chunkLoad.get()))
                .thenExecute(() -> {
                    assertBlock(helper, level, target, Blocks.GOLD_BLOCK);
                    level.setBlockAndUpdate(target, Blocks.DIAMOND_BLOCK.defaultBlockState());
                    releaseChunk(level, chunk);
                })
                .thenWaitUntil(() -> requireUnloaded(helper, level, chunk))
                .thenExecute(() -> startSave(
                        helper, runtime, zoneId.get(), "Unloaded diamond",
                        terminal, current))
                .thenWaitUntil(() -> requireIdle(helper, runtime, current))
                .thenExecute(() -> {
                    requireSucceeded(helper, terminal.get(), "Save unloaded diamond");
                    diamond.set(activeCommit(helper, runtime));
                    assertCommitBlock(
                            helper, runtime, diamond.get(), target, "minecraft:diamond_block");
                    startRestore(
                            helper, runtime, gold.get(), zoneId.get(),
                            terminal, current);
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime, current))
                .thenExecute(() -> {
                    requireSucceeded(helper, terminal.get(), "Restore unloaded gold");
                    chunkLoad.set(loadChunk(level, chunk));
                })
                .thenWaitUntil(() -> requireLoaded(helper, level, chunk, chunkLoad.get()))
                .thenExecute(() -> {
                    assertBlock(helper, level, target, Blocks.GOLD_BLOCK);
                    releaseChunk(level, chunk);
                })
                .thenWaitUntil(() -> requireUnloaded(helper, level, chunk))
                .thenExecute(() -> startRestore(
                        helper, runtime, diamond.get(), zoneId.get(),
                        terminal, current))
                .thenWaitUntil(() -> requireIdle(helper, runtime, current))
                .thenExecute(() -> {
                    requireSucceeded(helper, terminal.get(), "Restore unloaded diamond");
                    chunkLoad.set(loadChunk(level, chunk));
                })
                .thenWaitUntil(() -> requireLoaded(helper, level, chunk, chunkLoad.get()))
                .thenExecute(() -> {
                    assertBlock(helper, level, target, Blocks.DIAMOND_BLOCK);
                    releaseChunk(level, chunk);
                    LumiGameTestLease.release(lease);
                })
                .thenSucceed();
    }

    private static void startSave(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            UUID zoneId,
            String message,
            AtomicReference<MutationTerminalState> terminal,
            AtomicReference<DimensionMutation> current) {
        terminal.set(null);
        try {
            var expected = runtime.activeRef();
            current.set(runtime.startZoneSave(
                    expected, AUTHOR, AUTHOR.id(), zoneId, message,
                    operation -> terminal.set(operation.terminalState())));
        } catch (IOException failed) {
            throw helper.assertionException(
                    "Cannot start durable Save: %s", failed.getMessage());
        }
    }

    private static void startRestore(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            CommitId target,
            UUID zoneId,
            AtomicReference<MutationTerminalState> terminal,
            AtomicReference<DimensionMutation> current) {
        terminal.set(null);
        try {
            current.set(runtime.startZoneRestore(
                    target, zoneId, AUTHOR,
                    operation -> terminal.set(operation.terminalState())));
        } catch (IOException failed) {
            throw helper.assertionException(
                    "Cannot start durable Restore: %s", failed.getMessage());
        }
    }

    private static void acquireZone(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            UUID lease,
            AtomicReference<UUID> zoneId,
            BlockPos position) {
        LumiGameTestLease.acquire(helper, lease);
        if (zoneId.get() == null) {
            zoneId.set(createZone(helper, runtime, position));
        }
    }

    private static UUID createZone(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            BlockPos position) {
        SectionKey cell = new SectionKey(
                Math.floorDiv(position.getX(), 16),
                Math.floorDiv(position.getY(), 16),
                Math.floorDiv(position.getZ(), 16));
        try {
            UUID zoneId = runtime.createZone(
                    "History gate", 0x44AAFF, Set.of(cell)).id();
            runtime.setZoneActorActive(zoneId, AUTHOR.id(), true);
            return zoneId;
        } catch (IOException failed) {
            throw helper.assertionException(
                    "Cannot create history gate zone: %s", failed.getMessage());
        }
    }

    private static CompletableFuture<?> loadChunk(ServerLevel level, ChunkPos chunk) {
        return level.getChunkSource().addTicketAndLoadWithRadius(
                TEST_CHUNK_TICKET, chunk, 0);
    }

    private static void releaseChunk(ServerLevel level, ChunkPos chunk) {
        level.getChunkSource().removeTicketWithRadius(
                TEST_CHUNK_TICKET, chunk, 0);
    }

    private static void requireLoaded(
            GameTestHelper helper,
            ServerLevel level,
            ChunkPos chunk,
            CompletableFuture<?> loading) {
        helper.assertTrue(loading != null && loading.isDone(),
                "Test chunk load is not complete");
        helper.assertFalse(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null,
                "Test chunk is not loaded");
    }

    private static void requireUnloaded(
            GameTestHelper helper, ServerLevel level, ChunkPos chunk) {
        helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null,
                "Test chunk is still loaded");
    }

    private static void assertBlock(
            GameTestHelper helper,
            ServerLevel level,
            BlockPos target,
            net.minecraft.world.level.block.Block expected) {
        helper.assertValueEqual(
                expected.defaultBlockState(), level.getBlockState(target),
                "Unexpected restored block at " + target);
    }

    private static void assertCommitBlock(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            CommitId commitId,
            BlockPos target,
            String expected) {
        try {
            var objects = new WorldObjectRepository(runtime.repository());
            var commit = new CommitRepository(runtime.repository()).read(commitId);
            SectionKey key = MinecraftSectionCapture.key(target);
            var objectId = new WorldObjectGraph(objects).scan(commit.tree())
                    .leaves().get(key);
            helper.assertFalse(objectId == null,
                    "Commit does not contain tracked section " + key);
            String actual = objects.readSection(objectId).blockStates()
                    .get(MinecraftSectionCapture.localIndex(target));
            helper.assertValueEqual(expected, actual,
                    "Unexpected committed block at " + target);
        } catch (IOException failed) {
            throw helper.assertionException(
                    "Cannot inspect durable commit: %s", failed.getMessage());
        }
    }

    private static CommitId activeCommit(
            GameTestHelper helper, FabricDimensionRuntime runtime) {
        try {
            return runtime.activeRef().commit();
        } catch (IOException failed) {
            throw helper.assertionException(
                    "Cannot read active Lumi commit: %s", failed.getMessage());
        }
    }

    private static FabricDimensionRuntime runtime(GameTestHelper helper) {
        return LumiMod.serverRuntime().find(helper.getLevel())
                .orElseThrow(() -> helper.assertionException(
                        "Lumi runtime is not loaded"));
    }

    private static void requireIdle(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            AtomicReference<DimensionMutation> current) {
        helper.assertFalse(runtime.operations().hasActiveOperation()
                        || runtime.operations().queuedCount() > 0,
                "Lumi operation is still active: "
                        + current.get().progress().phase());
    }

    private static void requireSucceeded(
            GameTestHelper helper,
            MutationTerminalState terminal,
            String operation) {
        helper.assertValueEqual(
                MutationTerminalState.SUCCEEDED, terminal,
                operation + " did not succeed");
    }
}
