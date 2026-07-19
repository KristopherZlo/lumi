package io.github.lumi.gametest;

import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.world_modification.BlockBuffer;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.LiveActionOperation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Real Axiom SetBuffer gate for low-level bulk section writes. */
public final class LumiAxiomGameTests {
    private static final BlockPos FIRST = new BlockPos(2, 2, 2);
    private static final BlockPos SECOND = new BlockPos(3, 2, 2);

    @GameTest(maxTicks = 300000)
    public void axiomBufferIsExactAndConflictIsAtomic(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        AtomicReference<ServerPlayer> player = new AtomicReference<>();
        AtomicReference<LiveActionOperation> conflicted = new AtomicReference<>();
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
                })
                .thenExecute(() -> releasePlayer(helper, player.get(), test))
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
