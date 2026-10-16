package io.github.lumi.gametest;

import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity.ManipulateEntry;
import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity.PassengerManipulation;
import com.moulberry.axiom.packets.AxiomServerboundSpawnEntity;
import com.moulberry.axiom.packets.AxiomServerboundSpawnEntity.SpawnEntry;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockBox;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.phys.Vec3;

/** Real serialized Axiom entity requests followed by Lumi history and durable reopen. */
@SuppressWarnings("UnstableApiUsage")
final class LumiAxiomEntityClientScenario {
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID rider = UUID.randomUUID();

    void run(ClientGameTestContext context, TestSingleplayerContext world, LumiBehaviorReport report,
            BlockPos origin, List<BlockBox> area) throws IOException {
        var server = world.getServer();
        var operations = new LumiBehaviorOperations(context, server, report);
        var checks = new LumiBehaviorChecks(context, world, report);
        Vec3 start = Vec3.atBottomCenterOf(origin.above(2));
        context.runOnClient(client -> new AxiomServerboundSpawnEntity(List.of(
                spawn(first, start), spawn(second, start.add(4, 0, 0)), spawn(rider, start.add(2, 0, 0)))).send());
        for (int tick = 0; ; tick++) {
            if (server.computeOnServer(minecraft -> {
                var level = minecraft.getPlayerList().getPlayers().getFirst().level();
                return level.getEntity(first) != null && level.getEntity(second) != null && level.getEntity(rider) != null;
            })) break;
            if (tick == 200) throw new AssertionError("Axiom serialized entity spawn did not reach the server");
            context.waitTick();
        }
        context.runOnClient(client -> new AxiomServerboundManipulateEntity(List.of(
                new ManipulateEntry(first, PassengerManipulation.ADD_LIST, List.of(rider)))).send());
        awaitVehicle(context, world, first);
        operations.awaitDurability("axiom_entity_baseline");
        var baseline = operations.save("axiom-entities-before", area);
        context.runOnClient(client -> {
            var nbt = new CompoundTag();
            var tags = new ListTag();
            tags.add(StringTag.valueOf("lumi-axiom-edited"));
            nbt.put("Tags", tags);
            new AxiomServerboundManipulateEntity(List.of(
                    new ManipulateEntry(first, PassengerManipulation.REMOVE_LIST, List.of(rider)),
                    new ManipulateEntry(second, PassengerManipulation.ADD_LIST, List.of(rider)),
                    new ManipulateEntry(first, Set.of(), start.add(48, 0, 0), 90, 30, nbt,
                            PassengerManipulation.NONE, List.of()))).send();
        });
        awaitVehicle(context, world, second);
        boolean moved = server.computeOnServer(minecraft -> {
            var entity = minecraft.getPlayerList().getPlayers().getFirst().level().getEntity(first);
            return entity.position().equals(start.add(48, 0, 0)) && entity.getYRot() == 90
                    && entity.getXRot() == 30 && entity.getTags().contains("lumi-axiom-edited");
        });
        if (!moved) throw new AssertionError("Axiom serialized NBT/pose edit differs");
        operations.awaitDurability("axiom_entity_edited");
        var editedState = checks.snapshot("axiom_entities_edited", area);
        operations.undo("axiom_entities");
        checks.assertSnapshot("axiom_entities_undo", area, baseline.snapshot());
        operations.redo("axiom_entities");
        checks.assertSnapshot("axiom_entities_redo", area, editedState);
        var edited = operations.save("axiom-entities-after", area);
        var comparison = server.computeOnServer(minecraft -> LumiMod.serverRuntime()
                .find(minecraft.getPlayerList().getPlayers().getFirst().level()).orElseThrow()
                .compare(baseline.commit(), edited.commit()));
        context.waitFor(client -> comparison.isDone());
        var summary = comparison.join();
        if (summary.changedBlocks() != 0 || summary.changedEntityChunks() == 0) {
            throw new AssertionError("Axiom entity Compare differs: " + summary);
        }
        operations.restore("axiom_entities_before", baseline.commit());
        checks.assertSnapshot("axiom_entities_restore_before", area, baseline.snapshot());
        operations.restore("axiom_entities_after", edited.commit());
        checks.assertSnapshot("axiom_entities_restore_after", area, edited.snapshot());
        report.event("gate", "axiom_entity_client_history", "succeeded", 0, 0,
                "serialized spawn/manipulation; passenger roots; NBT; cross-chunk pose; Compare; Undo/Redo; Save/Restore");
    }

    private static SpawnEntry spawn(UUID id, Vec3 position) {
        var nbt = new CompoundTag();
        nbt.putString("id", "minecraft:armor_stand");
        nbt.putBoolean("NoGravity", true);
        return new SpawnEntry(id, position, 0, 0, null, nbt);
    }

    private void awaitVehicle(ClientGameTestContext context, TestSingleplayerContext world, UUID vehicle) {
        for (int tick = 0; ; tick++) {
            if (world.getServer().computeOnServer(minecraft -> {
                var passenger = minecraft.getPlayerList().getPlayers().getFirst().level().getEntity(rider);
                return passenger != null && passenger.getVehicle() != null && passenger.getVehicle().getUUID().equals(vehicle);
            })) return;
            if (tick == 200) throw new AssertionError("Axiom serialized passenger request did not reach the server");
            context.waitTick();
        }
    }
}
