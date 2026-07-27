package io.github.lumi.gametest;

import com.moulberry.axiom.AxiomServer;
import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.packets.AxiomServerboundSetBlock;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.world_modification.BlockBuffer;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Real Axiom packet gates for low-level bulk and fast-place writes. */
public final class LumiAxiomGameTests {
    private static final BlockPos FIRST = new BlockPos(2, 2, 2);
    private static final BlockPos SECOND = new BlockPos(3, 2, 2);
    private static final BlockPos THIRD = new BlockPos(4, 2, 2);

    @GameTest(maxTicks = 300000)
    public void axiomInfiniteReachIsUndoable(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        AtomicReference<ServerPlayer> player = new AtomicReference<>();
        AtomicReference<DimensionMutation> undo = new AtomicReference<>();
        AtomicReference<DimensionMutation> redo = new AtomicReference<>();
        UUID test = UUID.randomUUID();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    player.set(helper.makeMockServerPlayerInLevel());
                    helper.setBlock(FIRST, Blocks.STONE);
                    helper.getLevel().getServer().getPlayerList()
                            .op(player.get().nameAndId(),
                                    Optional.of(LevelBasedPermissionSet.OWNER),
                                    Optional.empty());
                    player.get().setItemInHand(
                            InteractionHand.MAIN_HAND,
                            Blocks.GOLD_BLOCK.asItem().getDefaultInstance());
                    AxiomServer.onAxiomActive(player.get());
                    helper.assertTrue(AxiomServer.canUseAxiom(
                                    player.get(), AxiomPermission.BUILD_PLACE),
                            "Mock player lacks Axiom build permission");
                    helper.assertTrue(runtime.freeze().isMutationAllowed(),
                            "Lumi unexpectedly froze Axiom test mutations");
                    applyInfiniteReach(helper, player.get());
                    helper.assertBlockState(FIRST, Blocks.GOLD_BLOCK.defaultBlockState());
                    helper.assertTrue(runtime.liveActions()
                                    .prepareUndo(player.get().getUUID()).isPresent(),
                            "Axiom Infinite Reach did not create a live action");
                    undo.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.UNDO, ignored -> { }));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    if (undo.get() == null) {
                        return;
                    }
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED,
                            undo.get().terminalState(),
                            "Axiom Infinite Reach Undo must succeed");
                    helper.assertBlockState(FIRST, Blocks.STONE.defaultBlockState());
                    redo.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.REDO, ignored -> { }));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    if (redo.get() != null) {
                        helper.assertValueEqual(MutationTerminalState.SUCCEEDED,
                                redo.get().terminalState(),
                                "Axiom Infinite Reach Redo must succeed");
                        helper.assertBlockState(FIRST, Blocks.GOLD_BLOCK.defaultBlockState());
                    }
                    AxiomServer.activeAxiomPlayers.remove(player.get().getUUID());
                    helper.getLevel().getServer().getPlayerList()
                            .deop(player.get().nameAndId());
                    releasePlayer(helper, player.get(), test);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void axiomFastPlaceTntBurstUndoesAsOneAction(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        AtomicReference<ServerPlayer> player = new AtomicReference<>();
        AtomicReference<DimensionMutation> undo = new AtomicReference<>();
        AtomicReference<Set<UUID>> originalCarriers = new AtomicReference<>();
        UUID test = UUID.randomUUID();
        List<BlockPos> tnt = tntBurstPositions();
        List<BlockPos> ignition = tnt.stream().map(BlockPos::below).toList();
        List<BlockPos> inactivePower = tnt.stream()
                .filter(position -> position.getZ() == 2)
                .map(BlockPos::north)
                .toList();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    player.set(helper.makeMockServerPlayerInLevel());
                    helper.getLevel().getServer().getPlayerList()
                            .op(player.get().nameAndId(),
                                    Optional.of(LevelBasedPermissionSet.OWNER),
                                    Optional.empty());
                    player.get().setItemInHand(
                            InteractionHand.MAIN_HAND,
                            Blocks.REDSTONE_BLOCK.asItem().getDefaultInstance());
                    AxiomServer.onAxiomActive(player.get());
                    tnt.forEach(position -> helper.setBlock(position, Blocks.TNT));
                    ignition.forEach(position -> applyInfiniteReach(
                            helper, player.get(), position,
                            Blocks.REDSTONE_BLOCK.defaultBlockState()));
                    helper.assertEntitiesPresent(EntityType.TNT, tnt.size());
                    originalCarriers.set(helper.getEntities(EntityType.TNT).stream()
                            .map(entity -> entity.getUUID())
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
                    inactivePower.forEach(position -> applyInfiniteReach(
                            helper, player.get(), position,
                            Blocks.REDSTONE_BLOCK.defaultBlockState()));
                    List<PrimedTnt> carriers = helper.getEntities(EntityType.TNT);
                    for (int index = 0; index < carriers.size(); index++) {
                        carriers.get(index).setFuse(index < 10 ? 2 : 200);
                    }
                })
                .thenWaitUntil(() -> {
                    long active = helper.getEntities(EntityType.TNT).stream()
                            .filter(entity -> !entity.isRemoved())
                            .count();
                    helper.assertTrue(active > 0 && active < tnt.size(),
                            "The 40-carrier burst has not entered its explosion phase");
                })
                .thenExecute(() -> {
                    undo.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.UNDO, ignored -> { }));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED,
                            undo.get().terminalState(),
                            "Fast Place TNT burst Undo must succeed");
                    tnt.forEach(position -> helper.assertBlockState(
                            position, Blocks.TNT.defaultBlockState()));
                    ignition.forEach(position -> helper.assertBlockState(
                            position, Blocks.AIR.defaultBlockState()));
                    inactivePower.forEach(position -> helper.assertBlockState(
                            position, Blocks.AIR.defaultBlockState()));
                    var active = helper.getEntities(EntityType.TNT).stream()
                            .filter(entity -> !entity.isRemoved())
                            .toList();
                    helper.assertTrue(active.isEmpty(),
                            "Fast Place TNT burst left active carriers: "
                                    + active.stream().map(entity ->
                                            entity.getUUID() + " original="
                                                    + originalCarriers.get().contains(
                                                            entity.getUUID())
                                                    + " fuse=" + entity.getFuse()
                                                    + " pos=" + entity.position())
                                            .toList());
                    AxiomServer.activeAxiomPlayers.remove(player.get().getUUID());
                    helper.getLevel().getServer().getPlayerList()
                            .deop(player.get().nameAndId());
                    releasePlayer(helper, player.get(), test);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void axiomBufferIsExactAndConflictIsAtomic(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        AtomicReference<ServerPlayer> player = new AtomicReference<>();
        AtomicReference<DimensionMutation> conflicted = new AtomicReference<>();
        AtomicReference<DimensionMutation> liveUndo = new AtomicReference<>();
        AtomicReference<DimensionMutation> axiomUndo = new AtomicReference<>();
        UUID secondPlayer = UUID.randomUUID();
        UUID test = UUID.randomUUID();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    player.set(helper.makeMockServerPlayerInLevel());
                    helper.setBlock(FIRST, Blocks.STONE);
                    helper.setBlock(SECOND, Blocks.STONE);
                    applyBuffer(helper, player.get());
                    helper.assertBlockState(FIRST, Blocks.GOLD_BLOCK.defaultBlockState());
                    helper.assertBlockState(SECOND, Blocks.GOLD_BLOCK.defaultBlockState());
                    helper.assertTrue(runtime.mutations().hasPendingBuilderChanges(),
                            "Axiom edit must mark the builder draft");
                    runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.UNDO, ignored -> { });
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertBlockState(FIRST, Blocks.STONE.defaultBlockState());
                    helper.assertBlockState(SECOND, Blocks.STONE.defaultBlockState());
                    runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.REDO, ignored -> { });
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertBlockState(FIRST, Blocks.GOLD_BLOCK.defaultBlockState());
                    helper.assertBlockState(SECOND, Blocks.GOLD_BLOCK.defaultBlockState());
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), secondPlayer)) {
                        helper.getLevel().setBlock(helper.absolutePos(SECOND),
                                Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    conflicted.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.UNDO, ignored -> { }));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.FAILED,
                            conflicted.get().terminalState(),
                            "Overlapping Axiom Undo must fail");
                    helper.assertBlockState(FIRST, Blocks.GOLD_BLOCK.defaultBlockState());
                    helper.assertBlockState(SECOND, Blocks.DIAMOND_BLOCK.defaultBlockState());
                    var builderBeforeNoOp = runtime.mutations().builderSnapshot();
                    applyCurrentBuffer(helper, player.get());
                    helper.assertValueEqual(builderBeforeNoOp,
                            runtime.mutations().builderSnapshot(),
                            "No-op Axiom buffer must not advance builder generations");
                    helper.setBlock(THIRD, Blocks.COMMAND_BLOCK);
                    helper.assertTrue(
                            helper.getLevel().getBlockEntity(helper.absolutePos(THIRD)) != null,
                            "Command block fixture has no block entity");
                    applyBuffer(helper, player.get(), THIRD, Blocks.AIR.defaultBlockState());
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), player.get().getUUID())) {
                        helper.getLevel().setBlock(helper.absolutePos(THIRD),
                                Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    liveUndo.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.UNDO, ignored -> { }));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED,
                            liveUndo.get().terminalState(),
                            "Undo after Axiom block-entity removal must succeed");
                    helper.assertBlockState(THIRD, Blocks.AIR.defaultBlockState());
                    axiomUndo.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.UNDO, ignored -> { }));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED,
                            axiomUndo.get().terminalState(),
                            "Axiom block-entity removal Undo must succeed");
                    helper.assertBlockState(
                            THIRD, Blocks.COMMAND_BLOCK.defaultBlockState());
                    helper.assertTrue(
                            helper.getLevel().getBlockEntity(helper.absolutePos(THIRD)) != null,
                            "Axiom Undo did not restore the command block entity");
                    releasePlayer(helper, player.get(), test);
                })
                .thenSucceed();
    }

    private static void applyBuffer(GameTestHelper helper, ServerPlayer player) {
        BlockBuffer buffer = new BlockBuffer();
        BlockPos first = helper.absolutePos(FIRST);
        BlockPos second = helper.absolutePos(SECOND);
        buffer.set(first.getX(), first.getY(), first.getZ(), Blocks.GOLD_BLOCK.defaultBlockState());
        buffer.set(second.getX(), second.getY(), second.getZ(), Blocks.GOLD_BLOCK.defaultBlockState());
        AxiomServerboundSetBuffer.applyBlockBufferServer(
                buffer, helper.getLevel(), null, player);
    }

    private static void applyCurrentBuffer(GameTestHelper helper, ServerPlayer player) {
        BlockBuffer buffer = new BlockBuffer();
        BlockPos first = helper.absolutePos(FIRST);
        BlockPos second = helper.absolutePos(SECOND);
        buffer.set(first.getX(), first.getY(), first.getZ(),
                helper.getLevel().getBlockState(first));
        buffer.set(second.getX(), second.getY(), second.getZ(),
                helper.getLevel().getBlockState(second));
        AxiomServerboundSetBuffer.applyBlockBufferServer(
                buffer, helper.getLevel(), null, player);
    }

    private static void applyBuffer(
            GameTestHelper helper,
            ServerPlayer player,
            BlockPos relative,
            BlockState state) {
        BlockBuffer buffer = new BlockBuffer();
        BlockPos position = helper.absolutePos(relative);
        buffer.set(position.getX(), position.getY(), position.getZ(), state);
        AxiomServerboundSetBuffer.applyBlockBufferServer(
                buffer, helper.getLevel(), null, player);
    }

    private static void applyInfiniteReach(GameTestHelper helper, ServerPlayer player) {
        applyInfiniteReach(
                helper, player, FIRST, Blocks.GOLD_BLOCK.defaultBlockState());
    }

    private static void applyInfiniteReach(
            GameTestHelper helper,
            ServerPlayer player,
            BlockPos relative,
            BlockState state) {
        BlockPos position = helper.absolutePos(relative);
        new AxiomServerboundSetBlock(
                Map.of(position, state),
                true,
                AxiomServerboundSetBlock.REASON_INFINITEREACH,
                false,
                new BlockHitResult(
                        Vec3.atCenterOf(position), Direction.UP, position, false),
                InteractionHand.MAIN_HAND,
                -1)
                .handle(helper.getLevel().getServer(), player);
    }

    private static List<BlockPos> tntBurstPositions() {
        List<BlockPos> positions = new ArrayList<>(40);
        for (int y : List.of(2, 4)) {
            for (int x = 1; x <= 5; x++) {
                for (int z = 2; z <= 5; z++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        return List.copyOf(positions);
    }

    private static void releasePlayer(
            GameTestHelper helper, ServerPlayer player, UUID test) {
        helper.getLevel().getServer().getPlayerList().remove(player);
        LumiGameTestLease.release(test);
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
