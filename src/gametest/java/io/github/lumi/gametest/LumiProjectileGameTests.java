package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.domain.service.SaveRequest;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Exact continuation gate for projectile-triggered world actions. */
public final class LumiProjectileGameTests {
    private static final BlockPos CRYSTAL = new BlockPos(4, 3, 4);
    private static final BlockPos AMBIENT = new BlockPos(1, 2, 1);

    @GameTest(maxTicks = 300000)
    public void arrowImpactRemainsOwnedThroughCrystalExplosion(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        AtomicReference<Map<BlockPos, BlockState>> baseline = new AtomicReference<>();
        AtomicReference<UUID> crystalId = new AtomicReference<>();
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    prepareVolume(helper);
                    EndCrystal crystal = spawnCrystal(helper);
                    crystalId.set(crystal.getUUID());
                    baseline.set(snapshot(helper));
                    shootArrow(helper, runtime, player, crystal);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        helper.getLevel().getEntityInAnyDimension(crystalId.get()) == null,
                        "Arrow has not destroyed the end crystal"))
                .thenIdle(20)
                .thenExecute(() -> runtime.startLiveAction(
                        player, LiveActionJournal.Direction.UNDO,
                        operation -> terminal.set(operation.terminalState())))
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> helper.assertValueEqual(
                        MutationTerminalState.SUCCEEDED, terminal.get(),
                        "Arrow-triggered explosion Undo must succeed"))
                .thenExecute(() -> assertSnapshot(helper, baseline.get()))
                .thenExecute(() -> helper.assertTrue(
                        helper.getLevel().getEntityInAnyDimension(crystalId.get())
                                instanceof EndCrystal,
                        "Undo did not restore the same end crystal"))
                .thenExecute(() -> helper.assertEntityNotPresent(EntityType.ITEM))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void fullQuickRestoreRestoresAmbientAndIsUndoable(
            GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID player = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        CommitAuthor author = new CommitAuthor(player, "Projectile gate");
        AtomicReference<Arrow> arrow = new AtomicReference<>();
        AtomicReference<UUID> action = new AtomicReference<>();
        AtomicReference<MutationTerminalState> saveTerminal = new AtomicReference<>();
        AtomicReference<MutationTerminalState> rollbackTerminal = new AtomicReference<>();
        AtomicReference<MutationTerminalState> undoTerminal = new AtomicReference<>();
        AtomicReference<BlockState> ambientBaseline = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    Arrow spawned = createArrow(helper);
                    spawned.setNoGravity(true);
                    spawned.setDeltaMovement(Vec3.ZERO);
                    try (var ignored = DirectLiveActionContext.open(
                            runtime.liveActions(), player)) {
                        action.set(DirectLiveActionContext.current(
                                runtime.liveActions()).orElseThrow());
                        helper.assertTrue(helper.getLevel().addFreshEntity(spawned),
                                "Cannot add saved arrow");
                    }
                    arrow.set(spawned);
                    helper.assertTrue(runtime.mutations().hasPendingBuilderChanges(),
                            "Arrow spawn did not create a builder draft");
                    startSave(helper, runtime, author, saveTerminal);
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED,
                            saveTerminal.get(), "Arrow baseline Save must succeed");
                    helper.assertFalse(runtime.mutations().hasPendingBuilderChanges(),
                            "Save did not clear the captured builder draft");
                    ambientBaseline.set(helper.getBlockState(AMBIENT));
                    helper.setBlock(AMBIENT, Blocks.DIAMOND_BLOCK);
                    try (var ignored = DirectLiveActionContext.resume(
                            runtime.liveActions(), action.get())) {
                        arrow.get().discard();
                    }
                    runtime.causalTicks().finishedCarrier(arrow.get());
                    helper.assertTrue(arrow.get().isRemoved(),
                            "Saved arrow was not removed");
                    helper.assertTrue(runtime.mutations().hasPendingBuilderChanges(),
                            "Post-Save removal was hidden by the action's root baseline");
                    try {
                        runtime.startQuickRollback(author,
                                operation -> rollbackTerminal.set(
                                        operation.terminalState()));
                    } catch (IOException failed) {
                        throw helper.assertionException(
                                "Cannot start Quick Restore: %s", failed.getMessage());
                    }
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED,
                            rollbackTerminal.get(), "Quick Restore must succeed");
                    helper.assertTrue(helper.getLevel().getEntityInAnyDimension(
                                    arrow.get().getUUID()) instanceof Arrow,
                            "Quick Restore did not restore the saved arrow");
                    helper.assertBlockState(AMBIENT, ambientBaseline.get());
                    helper.assertTrue(runtime.mutations().snapshot().generations().isEmpty(),
                            "Quick Restore did not clear its full pending boundary");
                    runtime.startLiveAction(player, LiveActionJournal.Direction.UNDO,
                            operation -> undoTerminal.set(operation.terminalState()));
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED,
                            undoTerminal.get(), "Quick Restore Undo must succeed");
                    helper.assertTrue(helper.getLevel().getEntityInAnyDimension(
                                    arrow.get().getUUID()) == null,
                            "Undo did not remove the arrow restored by Quick Restore");
                    helper.assertBlockState(
                            AMBIENT, Blocks.DIAMOND_BLOCK.defaultBlockState());
                })
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }

    private static Arrow createArrow(GameTestHelper helper) {
        Arrow arrow = EntityType.ARROW.create(
                helper.getLevel(), EntitySpawnReason.COMMAND);
        if (arrow == null) {
            throw helper.assertionException("Cannot create arrow");
        }
        arrow.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(3, 4, 3))));
        return arrow;
    }

    private static void startSave(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            CommitAuthor author,
            AtomicReference<MutationTerminalState> terminal) {
        try {
            runtime.startSave(new SaveRequest(
                    runtime.activeRef(), author, "Saved carrier baseline", Instant.now(),
                    runtime.activeWorkspaceId(), Optional.empty(), CommitKind.MANUAL),
                    operation -> terminal.set(operation.terminalState()));
        } catch (IOException failed) {
            throw helper.assertionException(
                    "Cannot start carrier baseline Save: %s", failed.getMessage());
        }
    }

    private static EndCrystal spawnCrystal(GameTestHelper helper) {
        EndCrystal crystal = EntityType.END_CRYSTAL.create(
                helper.getLevel(), EntitySpawnReason.COMMAND);
        if (crystal == null) {
            throw helper.assertionException("Cannot create end crystal");
        }
        crystal.setPos(Vec3.atBottomCenterOf(helper.absolutePos(CRYSTAL)));
        helper.assertTrue(helper.getLevel().addFreshEntity(crystal),
                "Cannot add end crystal");
        return crystal;
    }

    private static void shootArrow(
            GameTestHelper helper,
            FabricDimensionRuntime runtime,
            UUID player,
            EndCrystal crystal) {
        Arrow arrow = EntityType.ARROW.create(
                helper.getLevel(), EntitySpawnReason.COMMAND);
        if (arrow == null) {
            throw helper.assertionException("Cannot create arrow");
        }
        arrow.setPos(crystal.getX() - 3, crystal.getY() + 1, crystal.getZ());
        arrow.shoot(1, 0, 0, 3, 0);
        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player)) {
            helper.assertTrue(helper.getLevel().addFreshEntity(arrow), "Cannot add arrow");
        }
    }

    private static void prepareVolume(GameTestHelper helper) {
        for (int x = 1; x <= 7; x++) for (int y = 1; y <= 5; y++) {
            for (int z = 1; z <= 7; z++) helper.setBlock(x, y, z, Blocks.STONE);
        }
        helper.setBlock(CRYSTAL.below(), Blocks.OBSIDIAN);
        helper.setBlock(CRYSTAL, Blocks.AIR);
        helper.setBlock(CRYSTAL.above(), Blocks.AIR);
        helper.setBlock(CRYSTAL.above(2), Blocks.AIR);
        for (int x = 1; x < CRYSTAL.getX(); x++) {
            helper.setBlock(x, CRYSTAL.getY() + 1, CRYSTAL.getZ(), Blocks.AIR);
        }
    }

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper helper) {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        for (int x = 1; x <= 7; x++) for (int y = 1; y <= 5; y++) {
            for (int z = 1; z <= 7; z++) {
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
