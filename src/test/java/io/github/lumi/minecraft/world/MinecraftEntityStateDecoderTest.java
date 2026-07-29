package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftEntityStateDecoderTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void resolvesTypeAndRestoresIdentityIntoPreparedNbt() throws Exception {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var payload = new net.minecraft.nbt.CompoundTag();
        payload.putString("CustomName", "marker");
        var source = new EntityChunkBlob(List.of(new EntityState(
                id, "minecraft:armor_stand", MinecraftNbtCodec.encode(payload))));

        DecodedEntity decoded = new MinecraftEntityStateDecoder(
                BuiltInRegistries.ENTITY_TYPE).decode(source).entities().getFirst();

        assertEquals(EntityType.ARMOR_STAND, decoded.type());
        assertEquals(id, decoded.id());
        assertEquals(source.entities().getFirst(), decoded.state());
        assertEquals("minecraft:armor_stand", decoded.nbt().getStringOr("id", ""));
        assertEquals(id, net.minecraft.core.UUIDUtil.uuidFromIntArray(
                decoded.nbt().getIntArray("UUID").orElseThrow()));
        assertEquals("marker", decoded.nbt().getStringOr("CustomName", ""));
    }

    @Test
    void rejectsMissingEntityTypeDuringPreparation() throws Exception {
        var source = new EntityChunkBlob(List.of(new EntityState(
                new UUID(0, 1), "missing:not_an_entity",
                MinecraftNbtCodec.encode(new net.minecraft.nbt.CompoundTag()))));

        assertThrows(IOException.class, () -> new MinecraftEntityStateDecoder(
                BuiltInRegistries.ENTITY_TYPE).decode(source));
    }

    @Test
    void removesLegacyTopLevelPassengerDuplicatedInsideVehicleNbt() throws Exception {
        UUID vehicleId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID passengerId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        CompoundTag passenger = new CompoundTag();
        passenger.putString("id", "minecraft:armor_stand");
        passenger.putIntArray("UUID", UUIDUtil.uuidToIntArray(passengerId));
        ListTag passengers = new ListTag();
        passengers.add(passenger);
        CompoundTag vehicle = new CompoundTag();
        vehicle.put("Passengers", passengers);
        var source = new EntityChunkBlob(List.of(
                new EntityState(vehicleId, "minecraft:armor_stand", MinecraftNbtCodec.encode(vehicle)),
                new EntityState(passengerId, "minecraft:armor_stand",
                        MinecraftNbtCodec.encode(new CompoundTag()))));
        var decoder = new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE);

        EntityChunkBlob normalized = decoder.normalize(source);

        assertEquals(List.of(vehicleId), normalized.entities().stream()
                .map(EntityState::id).toList());
        assertEquals(List.of(vehicleId), decoder.decode(source).entities().stream()
                .map(DecodedEntity::id).toList());
    }

    @Test
    void removesLegacyPassengerEvenWhenItsTopLevelRecordUsesAnotherChunk() throws Exception {
        UUID vehicleId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID passengerId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        CompoundTag passenger = new CompoundTag();
        passenger.putIntArray("UUID", UUIDUtil.uuidToIntArray(passengerId));
        ListTag passengers = new ListTag();
        passengers.add(passenger);
        CompoundTag vehicle = new CompoundTag();
        vehicle.put("Passengers", passengers);
        var decoder = new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE);

        Map<io.github.lumi.domain.model.EntityChunkKey, EntityChunkBlob> normalized =
                decoder.normalize(Map.of(
                        new io.github.lumi.domain.model.EntityChunkKey(0, 0),
                        new EntityChunkBlob(List.of(new EntityState(vehicleId,
                                "minecraft:oak_boat", MinecraftNbtCodec.encode(vehicle)))),
                        new io.github.lumi.domain.model.EntityChunkKey(1, 0),
                        new EntityChunkBlob(List.of(new EntityState(passengerId,
                                "minecraft:armor_stand",
                                MinecraftNbtCodec.encode(new CompoundTag()))))));

        assertEquals(1, normalized.values().stream()
                .mapToInt(chunk -> chunk.entities().size()).sum());
    }

    @Test
    void rejectsPassengerUuidDuplicatedAcrossRootGraphs() throws Exception {
        UUID passengerId =
                UUID.fromString("20000000-0000-0000-0000-000000000002");
        var decoder = new MinecraftEntityStateDecoder(
                BuiltInRegistries.ENTITY_TYPE);

        assertThrows(IOException.class, () -> decoder.normalize(Map.of(
                new EntityChunkKey(0, 0), vehicle(
                        new UUID(0, 1), passengerId),
                new EntityChunkKey(1, 0), vehicle(
                        new UUID(0, 2), passengerId))));
    }

    @Test
    void rejectsLegacyEntityUuidDuplicatedAcrossChunks() throws Exception {
        UUID entityId = UUID.fromString("30000000-0000-0000-0000-000000000003");
        EntityChunkKey first = new EntityChunkKey(0, 0);
        EntityChunkKey second = new EntityChunkKey(1, 0);

        assertThrows(IOException.class, () ->
                new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE)
                        .normalize(Map.of(
                                first, new EntityChunkBlob(List.of(
                                        rabbitAt(entityId, 1.0D))),
                                second, new EntityChunkBlob(List.of(
                                        rabbitAt(entityId, 17.0D))))));
    }

    @Test
    void normalizesLegacyEntityPayloadBeforeRestorePreparation() throws Exception {
        UUID entityId = UUID.fromString("30000000-0000-0000-0000-000000000003");
        CompoundTag legacy = entityPayload(488.9948F, -1285.8915F,
                "minecraft:movement_speed", "minecraft:attack_damage");
        EntityChunkKey key = new EntityChunkKey(2, 3);
        var source = Map.of(key, new EntityChunkBlob(List.of(new EntityState(
                entityId, "minecraft:bat", MinecraftNbtCodec.encode(legacy)))));

        EntityState normalized = new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE)
                .normalize(source).get(key).entities().getFirst();
        CompoundTag payload = MinecraftNbtCodec.decode(normalized.nbt());

        assertEquals(488.9948F % 360.0F,
                payload.getListOrEmpty("Rotation").getFloatOr(0, Float.NaN));
        assertEquals(-90.0F,
                payload.getListOrEmpty("Rotation").getFloatOr(1, Float.NaN));
        assertEquals("minecraft:attack_damage", payload.getListOrEmpty("attributes")
                .getCompoundOrEmpty(0).getStringOr("id", ""));
        assertEquals("minecraft:movement_speed", payload.getListOrEmpty("attributes")
                .getCompoundOrEmpty(1).getStringOr("id", ""));
    }

    @Test
    void removesMaterializedDefaultAttributesBeforeRestoreVerification() throws Exception {
        UUID entityId = UUID.fromString("40000000-0000-0000-0000-000000000004");
        CompoundTag payload = new CompoundTag();
        ListTag attributes = new ListTag();
        attributes.add(attribute("minecraft:movement_speed", 0.7D));
        attributes.add(attribute("minecraft:armor", 0.0D));
        attributes.add(attribute("minecraft:armor_toughness", 0.0D));
        payload.put("attributes", attributes);
        var source = new EntityChunkBlob(List.of(new EntityState(
                entityId, "minecraft:armor_stand", MinecraftNbtCodec.encode(payload))));

        EntityState normalized = new MinecraftEntityStateDecoder(
                BuiltInRegistries.ENTITY_TYPE).normalize(source).entities().getFirst();

        assertEquals(new CompoundTag(), MinecraftNbtCodec.decode(normalized.nbt()));
    }

    private static CompoundTag entityPayload(
            float yaw, float pitch, String firstAttribute, String secondAttribute) {
        CompoundTag entity = new CompoundTag();
        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(yaw));
        rotation.add(FloatTag.valueOf(pitch));
        entity.put("Rotation", rotation);
        ListTag attributes = new ListTag();
        attributes.add(attribute(firstAttribute));
        attributes.add(attribute(secondAttribute));
        entity.put("attributes", attributes);
        return entity;
    }

    private static EntityState rabbitAt(UUID id, double x) throws IOException {
        CompoundTag entity = new CompoundTag();
        ListTag position = new ListTag();
        position.add(DoubleTag.valueOf(x));
        position.add(DoubleTag.valueOf(64.0D));
        position.add(DoubleTag.valueOf(1.0D));
        entity.put("Pos", position);
        return new EntityState(
                id, "minecraft:rabbit", MinecraftNbtCodec.encode(entity));
    }

    private static EntityChunkBlob vehicle(UUID id, UUID passengerId)
            throws IOException {
        CompoundTag passenger = new CompoundTag();
        passenger.putString("id", "minecraft:armor_stand");
        passenger.putIntArray("UUID", UUIDUtil.uuidToIntArray(passengerId));
        ListTag passengers = new ListTag();
        passengers.add(passenger);
        CompoundTag vehicle = new CompoundTag();
        vehicle.put("Passengers", passengers);
        return new EntityChunkBlob(List.of(new EntityState(
                id, "minecraft:oak_boat", MinecraftNbtCodec.encode(vehicle))));
    }

    private static CompoundTag attribute(String id) {
        CompoundTag attribute = new CompoundTag();
        attribute.putString("id", id);
        return attribute;
    }

    private static CompoundTag attribute(String id, double base) {
        CompoundTag attribute = attribute(id);
        attribute.putDouble("base", base);
        return attribute;
    }
}
