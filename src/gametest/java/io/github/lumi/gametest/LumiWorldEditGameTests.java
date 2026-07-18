package io.github.lumi.gametest;

import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.fabric.FabricPermissionsProvider;
import com.sk89q.worldedit.fabric.FabricWorldEdit;
import com.sk89q.worldedit.regions.selector.limit.SelectorLimits;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.operation.LiveActionOperation;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Real WorldEdit command gate for one-step live action grouping. */
public final class LumiWorldEditGameTests {
    private static final BlockPos FIRST = new BlockPos(2, 2, 2);
    private static final BlockPos SECOND = new BlockPos(3, 2, 2);
    private static final SelectorLimits NO_LIMITS = new SelectorLimits() {
        @Override
        public Optional<Integer> getPolygonVertexLimit() { return Optional.empty(); }

        @Override
        public Optional<Integer> getPolyhedronVertexLimit() { return Optional.empty(); }
    };

    @GameTest(maxTicks = 300000)
    public void worldEditCommandUndoRedoIsExact(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        AtomicReference<ServerPlayer> player = new AtomicReference<>();
        AtomicReference<LiveActionOperation> redo = new AtomicReference<>();
        UUID test = UUID.randomUUID();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    player.set(helper.makeMockServerPlayerInLevel());
                    executeSet(helper, player.get());
                    helper.assertBlockState(FIRST, Blocks.GOLD_BLOCK.defaultBlockState());
                    helper.assertBlockState(SECOND, Blocks.GOLD_BLOCK.defaultBlockState());
                    runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.UNDO, ignored -> { });
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertBlockState(FIRST, Blocks.STONE.defaultBlockState());
                    helper.assertBlockState(SECOND, Blocks.STONE.defaultBlockState());
                    helper.assertTrue(runtime.liveActions()
                                    .prepareRedo(player.get().getUUID()).isPresent(),
                            "WorldEdit action did not enter the redo stack");
                    redo.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.REDO, ignored -> { }));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertValueEqual(
                        MutationTerminalState.SUCCEEDED, redo.get().terminalState(),
                        "WorldEdit Redo must succeed"))
                .thenExecute(() -> helper.assertBlockState(
                        FIRST, Blocks.GOLD_BLOCK.defaultBlockState()))
                .thenExecute(() -> helper.assertBlockState(
                        SECOND, Blocks.GOLD_BLOCK.defaultBlockState()))
                .thenExecute(() -> releasePlayer(helper, player.get(), test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void newerOverlapRefusesWorldEditUndoAtomically(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        AtomicReference<ServerPlayer> player = new AtomicReference<>();
        UUID secondPlayer = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        AtomicReference<LiveActionOperation> conflicted = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    player.set(helper.makeMockServerPlayerInLevel());
                    executeSet(helper, player.get());
                    helper.assertBlockState(FIRST, Blocks.GOLD_BLOCK.defaultBlockState());
                    helper.assertBlockState(SECOND, Blocks.GOLD_BLOCK.defaultBlockState());
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), secondPlayer)) {
                        helper.getLevel().setBlock(helper.absolutePos(SECOND),
                                Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    conflicted.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.UNDO,
                            ignored -> { }));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.FAILED,
                            conflicted.get().terminalState(),
                            "Overlapping WorldEdit Undo must fail");
                    helper.assertBlockState(FIRST, Blocks.GOLD_BLOCK.defaultBlockState());
                    helper.assertBlockState(SECOND, Blocks.DIAMOND_BLOCK.defaultBlockState());
                })
                .thenExecute(() -> releasePlayer(helper, player.get(), test))
                .thenSucceed();
    }

    private static void executeSet(
            GameTestHelper helper, ServerPlayer player) {
        helper.setBlock(FIRST, Blocks.STONE);
        helper.setBlock(SECOND, Blocks.STONE);
        select(helper, player, FIRST, SECOND);
        var previous = FabricWorldEdit.inst.getPermissionsProvider();
        FabricWorldEdit.inst.setPermissionsProvider(new FabricPermissionsProvider() {
            @Override
            public boolean hasPermission(ServerPlayer actor, String permission) {
                return actor == player;
            }

            @Override
            public void registerPermission(String permission) { }
        });
        try {
            helper.getLevel().getServer().getCommands().performPrefixedCommand(
                    player.createCommandSourceStack()
                            .withPermission(PermissionSet.ALL_PERMISSIONS)
                            .withSuppressedOutput(),
                    "//set minecraft:gold_block");
        } finally {
            FabricWorldEdit.inst.setPermissionsProvider(previous);
        }
    }

    private static void select(
            GameTestHelper helper,
            ServerPlayer player,
            BlockPos first,
            BlockPos second) {
        var world = FabricAdapter.adapt(helper.getLevel());
        var selector = FabricWorldEdit.inst.getSession(player).getRegionSelector(world);
        selector.selectPrimary(FabricAdapter.adapt(helper.absolutePos(first)), NO_LIMITS);
        selector.selectSecondary(FabricAdapter.adapt(helper.absolutePos(second)), NO_LIMITS);
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
