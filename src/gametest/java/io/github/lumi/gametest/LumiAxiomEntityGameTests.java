package io.github.lumi.gametest;

import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.packets.AxiomServerboundDeleteEntity;
import com.moulberry.axiom.packets.AxiomServerboundSpawnEntity;
import com.moulberry.axiom.packets.AxiomServerboundSpawnEntity.SpawnEntry;
import com.moulberry.axiom.utils.EntityDataUtils;
import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity.ManipulateEntry;
import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity.PassengerManipulation;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.MinecraftEntityMutationCapture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

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
                    Entity vehicle = helper.getLevel().getEntity(graph.get().second());
                    try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), UUID.randomUUID());
                            var capture = new MinecraftEntityMutationCapture(runtime)) {
                        capture.capture(List.of(vehicle));
                        vehicle.addTag("other-builder");
                    } catch (IOException failed) {
                        throw new UncheckedIOException(failed);
                    }
                    operation.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.UNDO, ignored -> { }));
                })
                .thenWaitUntil(() -> helper.assertFalse(runtime.operations().hasActiveOperation(), "Conflicting Undo is active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.FAILED, operation.get().terminalState(),
                            "Overlapping entity Undo must fail atomically");
                    graph.get().assertRiding(helper, graph.get().second());
                    helper.assertTrue(helper.getLevel().getEntity(graph.get().second()).getTags().contains("other-builder"),
                            "Conflicting Undo overwrote another builder's entity");
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

    @GameTest(maxTicks = 300000)
    public void axiomDeletingVehicleAndPassengerHasOneExactBeforeGraph(GameTestHelper helper) {
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
                    new AxiomServerboundDeleteEntity(List.of(graph.get().first(), graph.get().rider()))
                            .handle(helper.getLevel().getServer(), player.get());
                    helper.assertTrue(helper.getLevel().getEntity(graph.get().first()) == null
                                    && helper.getLevel().getEntity(graph.get().rider()) == null,
                            "Axiom did not delete both the vehicle and detached passenger");
                    operation.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.UNDO, ignored -> { }));
                })
                .thenWaitUntil(() -> helper.assertFalse(runtime.operations().hasActiveOperation(), "Delete Undo is active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED, operation.get().terminalState(),
                            "Axiom delete Undo duplicated a passenger root");
                    graph.get().assertRiding(helper, graph.get().first());
                    operation.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.REDO, ignored -> { }));
                })
                .thenWaitUntil(() -> helper.assertFalse(runtime.operations().hasActiveOperation(), "Delete Redo is active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED, operation.get().terminalState(),
                            "Axiom delete Redo failed");
                    helper.assertTrue(helper.getLevel().getEntity(graph.get().first()) == null
                                    && helper.getLevel().getEntity(graph.get().rider()) == null,
                            "Redo left a deleted vehicle or hidden passenger");
                    helper.getLevel().getEntity(graph.get().second()).discard();
                    LumiAxiomGameTests.deactivateAxiom(player.get());
                    helper.getLevel().getServer().getPlayerList().deop(player.get().nameAndId());
                    helper.getLevel().getServer().getPlayerList().remove(player.get());
                    LumiGameTestLease.release(test);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void axiomMarkerNbtAndCrossChunkPoseAreUndoable(GameTestHelper helper) {
        var runtime = LumiMod.serverRuntime().find(helper.getLevel()).orElseThrow();
        UUID test = UUID.randomUUID();
        AtomicReference<ServerPlayer> player = new AtomicReference<>();
        AtomicReference<MarkerFixture> marker = new AtomicReference<>();
        AtomicReference<DimensionMutation> operation = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    player.set(helper.makeMockServerPlayerInLevel());
                    helper.getLevel().getServer().getPlayerList().op(player.get().nameAndId(),
                            Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                    LumiAxiomGameTests.activateAxiom(player.get());
                    Entity entity = helper.spawn(EntityType.MARKER, new BlockPos(2, 2, 2));
                    marker.set(new MarkerFixture(entity.getUUID(), entity.position(), entity.getYRot(), entity.getXRot()));
                    CompoundTag nbt = new CompoundTag();
                    CompoundTag data = new CompoundTag();
                    data.putInt("counter", 42);
                    nbt.put("data", data);
                    var packet = new AxiomServerboundManipulateEntity(List.of(new ManipulateEntry(entity.getUUID(),
                            Set.of(), marker.get().position().add(48, 0, 0), 90, 30, nbt,
                            PassengerManipulation.NONE, List.of())));
                    var before = runtime.mutations().builderSnapshot();
                    var freeze = runtime.freeze().acquire();
                    try {
                        packet.handle(helper.getLevel().getServer(), player.get());
                        marker.get().assertState(helper, false);
                        helper.assertValueEqual(before, runtime.mutations().builderSnapshot(),
                                "Frozen marker manipulation advanced builder generations");
                    } finally {
                        freeze.release();
                    }
                    packet.handle(helper.getLevel().getServer(), player.get());
                    marker.get().assertState(helper, true);
                    operation.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.UNDO, ignored -> { }));
                })
                .thenWaitUntil(() -> helper.assertFalse(runtime.operations().hasActiveOperation(), "Marker Undo is active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED, operation.get().terminalState(),
                            "Marker manipulation Undo failed");
                    marker.get().assertState(helper, false);
                    operation.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.REDO, ignored -> { }));
                })
                .thenWaitUntil(() -> helper.assertFalse(runtime.operations().hasActiveOperation(), "Marker Redo is active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED, operation.get().terminalState(),
                            "Marker manipulation Redo failed");
                    marker.get().assertState(helper, true);
                    helper.getLevel().getEntity(marker.get().id()).discard();
                    LumiAxiomGameTests.deactivateAxiom(player.get());
                    helper.getLevel().getServer().getPlayerList().deop(player.get().nameAndId());
                    helper.getLevel().getServer().getPlayerList().remove(player.get());
                    LumiGameTestLease.release(test);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300000)
    public void axiomCopySpawnHonorsFreezeAndDuplicateUuid(GameTestHelper helper) {
        var runtime = LumiMod.serverRuntime().find(helper.getLevel()).orElseThrow();
        UUID test = UUID.randomUUID();
        UUID copy = UUID.randomUUID();
        AtomicReference<ServerPlayer> player = new AtomicReference<>();
        AtomicReference<UUID> source = new AtomicReference<>();
        AtomicReference<Vec3> target = new AtomicReference<>();
        AtomicReference<DimensionMutation> operation = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    player.set(helper.makeMockServerPlayerInLevel());
                    helper.getLevel().getServer().getPlayerList().op(player.get().nameAndId(),
                            Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                    LumiAxiomGameTests.activateAxiom(player.get());
                    Entity entity = helper.spawn(EntityType.MARKER, new BlockPos(2, 2, 2));
                    source.set(entity.getUUID());
                    target.set(entity.position().add(8, 0, 0));
                    var nbt = EntityDataUtils.saveWithoutId(entity);
                    var data = new CompoundTag();
                    data.putInt("counter", 42);
                    nbt.put("data", data);
                    EntityDataUtils.load(entity, nbt);
                    var packet = new AxiomServerboundSpawnEntity(List.of(
                            new SpawnEntry(copy, target.get(), 45, 10, source.get(), null)));
                    var before = runtime.mutations().builderSnapshot();
                    var freeze = runtime.freeze().acquire();
                    try {
                        packet.handle(helper.getLevel().getServer(), player.get());
                        helper.assertTrue(helper.getLevel().getEntity(copy) == null, "Frozen Axiom copy was spawned");
                        helper.assertValueEqual(before, runtime.mutations().builderSnapshot(), "Rejected spawn dirtied the builder draft");
                    } finally {
                        freeze.release();
                    }
                    packet.handle(helper.getLevel().getServer(), player.get());
                    assertCopiedMarker(helper, copy, source.get(), target.get());
                    var beforeDuplicate = runtime.mutations().builderSnapshot();
                    packet.handle(helper.getLevel().getServer(), player.get());
                    helper.assertValueEqual(beforeDuplicate, runtime.mutations().builderSnapshot(),
                            "Duplicate UUID spawn advanced builder generations");
                    operation.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.UNDO, ignored -> { }));
                })
                .thenWaitUntil(() -> helper.assertFalse(runtime.operations().hasActiveOperation(), "Copy Undo is active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED, operation.get().terminalState(), "Axiom copy Undo failed");
                    helper.assertTrue(helper.getLevel().getEntity(copy) == null
                            && helper.getLevel().getEntity(source.get()) != null, "Copy Undo changed the source or retained the copy");
                    operation.set(runtime.startLiveAction(player.get().getUUID(),
                            LiveActionJournal.Direction.REDO, ignored -> { }));
                })
                .thenWaitUntil(() -> helper.assertFalse(runtime.operations().hasActiveOperation(), "Copy Redo is active"))
                .thenExecute(() -> {
                    helper.assertValueEqual(MutationTerminalState.SUCCEEDED, operation.get().terminalState(), "Axiom copy Redo failed");
                    assertCopiedMarker(helper, copy, source.get(), target.get());
                    helper.getLevel().getEntity(copy).discard();
                    helper.getLevel().getEntity(source.get()).discard();
                    LumiAxiomGameTests.deactivateAxiom(player.get());
                    helper.getLevel().getServer().getPlayerList().deop(player.get().nameAndId());
                    helper.getLevel().getServer().getPlayerList().remove(player.get());
                    LumiGameTestLease.release(test);
                })
                .thenSucceed();
    }

    private static void assertCopiedMarker(GameTestHelper helper, UUID copy, UUID source, Vec3 position) {
        Entity entity = helper.getLevel().getEntity(copy);
        helper.assertTrue(entity != null && entity.getType() == EntityType.MARKER, "Axiom copy identity/type differs");
        helper.assertValueEqual(position, entity.position(), "Axiom copy position differs");
        helper.assertTrue(EntityDataUtils.saveWithoutId(entity).getCompoundOrEmpty("data").getIntOr("counter", -1) == 42,
                "Axiom copy lost source marker NBT");
        helper.assertTrue(helper.getLevel().getEntity(source) != null, "Axiom copy removed its source");
    }

    private record MarkerFixture(UUID id, Vec3 position, float yaw, float pitch) {
        void assertState(GameTestHelper helper, boolean changed) {
            Entity entity = helper.getLevel().getEntity(id);
            helper.assertTrue(entity != null, "Axiom marker disappeared");
            helper.assertValueEqual(changed ? position.add(48, 0, 0) : position, entity.position(),
                    "Axiom marker position differs");
            helper.assertTrue(entity.getYRot() == (changed ? 90 : yaw) && entity.getXRot() == (changed ? 30 : pitch),
                    "Axiom marker rotation differs");
            var data = EntityDataUtils.saveWithoutId(entity).getCompoundOrEmpty("data");
            helper.assertTrue(changed ? data.getIntOr("counter", -1) == 42 : data.isEmpty(),
                    "Axiom marker NBT differs: " + data);
        }
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
