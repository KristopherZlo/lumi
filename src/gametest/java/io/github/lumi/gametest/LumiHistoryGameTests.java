package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/** Integrated durable Save/Restore gates over the production dimension runtime. */
public final class LumiHistoryGameTests {
    private static final CommitAuthor AUTHOR =
            new CommitAuthor(new UUID(0, 7), "History gate");

    @GameTest(maxTicks = 60000)
    public void saveRestoreAddsAndRemovesDurableEntityExactly(GameTestHelper helper) {
        FabricDimensionRuntime runtime = runtime(helper);
        UUID lease = UUID.randomUUID();
        AtomicReference<Entity> entity = new AtomicReference<>();
        AtomicReference<CommitId> withEntity = new AtomicReference<>();
        AtomicReference<CommitId> withoutEntity = new AtomicReference<>();
        AtomicReference<UUID> zoneId = new AtomicReference<>();
        AtomicReference<MutationTerminalState> terminal = new AtomicReference<>();
        AtomicReference<DimensionMutation> current = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, lease))
                .thenExecute(() -> {
                    zoneId.set(createZone(helper, runtime));
                    entity.set(helper.spawn(
                            EntityType.ARMOR_STAND, new BlockPos(2, 2, 2)));
                    startSave(
                            helper, runtime, zoneId.get(), "With entity",
                            terminal, current);
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime, current))
                .thenExecute(() -> {
                    requireSucceeded(helper, terminal.get(), "Save with entity");
                    withEntity.set(activeCommit(helper, runtime));
                    entity.get().discard();
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
                    requireSucceeded(helper, terminal.get(), "Restore with entity");
                    helper.assertFalse(
                            helper.getLevel().getEntityInAnyDimension(
                                    entity.get().getUUID()) == null,
                            "Restore did not recreate the durable entity");
                    startRestore(
                            helper, runtime, withoutEntity.get(), zoneId.get(),
                            terminal, current);
                })
                .thenWaitUntil(() -> requireIdle(helper, runtime, current))
                .thenExecute(() -> {
                    requireSucceeded(helper, terminal.get(), "Restore without entity");
                    Entity restored = helper.getLevel().getEntityInAnyDimension(
                            entity.get().getUUID());
                    helper.assertTrue(restored == null || restored.isRemoved(),
                            "Restore left a durable entity absent from the target");
                })
                .thenExecute(() -> LumiGameTestLease.release(lease))
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

    private static UUID createZone(
            GameTestHelper helper, FabricDimensionRuntime runtime) {
        BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
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
