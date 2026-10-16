package io.github.lumi.gametest;

import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity.ManipulateEntry;
import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity.PassengerManipulation;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/** Exercises native Axiom entity handlers against exact durable passenger roots. */
public final class LumiAxiomEntityGameTests {
    @GameTest(maxTicks = 300000)
    public void axiomPassengerTransferIsAtomicAndUndoable(GameTestHelper helper) {
        var runtime = LumiMod.serverRuntime().find(helper.getLevel()).orElseThrow();
        UUID test = UUID.randomUUID();
        AtomicReference<ServerPlayer> player = new AtomicReference<>();
        AtomicReference<Graph> graph = new AtomicReference<>();
        AtomicReference<DimensionMutation> operation = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    player.set(helper.makeMockServerPlayerInLevel());
                    helper.getLevel().getServer().getPlayerList().op(player.get().nameAndId(),
                            Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                    LumiAxiomGameTests.activateAxiom(player.get());
                    graph.set(Graph.create(helper));
                    var packet = new AxiomServerboundManipulateEntity(List.of(
                            new ManipulateEntry(graph.get().first(), PassengerManipulation.REMOVE_LIST, List.of(graph.get().rider())),
                            new ManipulateEntry(graph.get().second(), PassengerManipulation.ADD_LIST, List.of(graph.get().rider()))));
                    var before = runtime.mutations().builderSnapshot();
                    var freeze = runtime.freeze().acquire();
                    try {
                        packet.handle(helper.getLevel().getServer(), player.get());
                        graph.get().assertRiding(helper, graph.get().first());
                        helper.assertValueEqual(before, runtime.mutations().builderSnapshot(),
                                "Rejected passenger transfer must not advance builder generations");
                    } finally {
                        freeze.release();
                    }
                    packet.handle(helper.getLevel().getServer(), player.get());
                    graph.get().assertRiding(helper, graph.get().second());
                    operation.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.UNDO, ignored -> { }));
                })
                .thenWaitUntil(() -> helper.assertFalse(runtime.operations().hasActiveOperation(), "Undo is active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED, operation.get().terminalState(),
                            "Passenger transfer Undo failed");
                    graph.get().assertRiding(helper, graph.get().first());
                    operation.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.REDO, ignored -> { }));
                })
                .thenWaitUntil(() -> helper.assertFalse(runtime.operations().hasActiveOperation(), "Redo is active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED, operation.get().terminalState(),
                            "Passenger transfer Redo failed");
                    graph.get().assertRiding(helper, graph.get().second());
                    for (UUID entity : List.of(graph.get().rider(), graph.get().first(), graph.get().second())) {
                        helper.getLevel().getEntity(entity).discard();
                    }
                    LumiAxiomGameTests.deactivateAxiom(player.get());
                    helper.getLevel().getServer().getPlayerList().deop(player.get().nameAndId());
                    helper.getLevel().getServer().getPlayerList().remove(player.get());
                    LumiGameTestLease.release(test);
                })
                .thenSucceed();
    }

    private record Graph(UUID first, UUID second, UUID rider) {
        static Graph create(GameTestHelper helper) {
            Entity first = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(2, 2, 2));
            Entity second = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(6, 2, 2));
            Entity rider = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(3, 2, 2));
            for (Entity entity : List.of(first, second, rider)) entity.setNoGravity(true);
            helper.assertTrue(rider.startRiding(first, true, true), "Cannot create passenger fixture");
            return new Graph(first.getUUID(), second.getUUID(), rider.getUUID());
        }

        void assertRiding(GameTestHelper helper, UUID vehicle) {
            Entity passenger = helper.getLevel().getEntity(rider);
            helper.assertTrue(passenger != null && passenger.getVehicle() != null
                            && passenger.getVehicle().getUUID().equals(vehicle),
                    "Passenger has the wrong vehicle after Axiom operation");
            UUID empty = first.equals(vehicle) ? second : first;
            helper.assertTrue(helper.getLevel().getEntity(empty).getPassengers().isEmpty(),
                    "Old vehicle retained a hidden passenger");
        }
    }
}
