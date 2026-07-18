package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

class MinecraftEntityNbtCanonicalizerTest {
    private final MinecraftEntityNbtCanonicalizer canonicalizer =
            new MinecraftEntityNbtCanonicalizer();

    @Test
    void makesRotationAndAttributesStableAcrossVanillaReload() throws Exception {
        CompoundTag legacy = entity(-2719.5906F, -451.0F,
                "minecraft:movement_speed", "minecraft:attack_damage");
        CompoundTag reloaded = entity(-2719.5906F % 360.0F, -90.0F,
                "minecraft:attack_damage", "minecraft:movement_speed");

        assertEquals(MinecraftNbtCodec.encode(canonicalizer.normalize(legacy)),
                MinecraftNbtCodec.encode(canonicalizer.normalize(reloaded)));
    }

    @Test
    void preservesUnrelatedListOrder() throws Exception {
        CompoundTag first = new CompoundTag();
        CompoundTag second = new CompoundTag();
        first.put("Items", strings("stone", "dirt"));
        second.put("Items", strings("dirt", "stone"));

        assertNotEquals(MinecraftNbtCodec.encode(canonicalizer.normalize(first)),
                MinecraftNbtCodec.encode(canonicalizer.normalize(second)));
    }

    @Test
    void normalizesPassengersWithoutRemovingPassengerIdentity() {
        CompoundTag passenger = entity(488.9948F, 15.0F,
                "minecraft:movement_speed", "minecraft:attack_damage");
        passenger.putString("id", "minecraft:rabbit");
        passenger.putIntArray("UUID", new int[] {1, 2, 3, 4});
        ListTag passengers = new ListTag();
        passengers.add(passenger);
        CompoundTag root = new CompoundTag();
        root.put("Passengers", passengers);

        CompoundTag normalized = canonicalizer.normalize(root)
                .getListOrEmpty("Passengers").getCompoundOrEmpty(0);

        assertEquals("minecraft:rabbit", normalized.getStringOr("id", ""));
        assertArrayEquals(new int[] {1, 2, 3, 4},
                normalized.getIntArray("UUID").orElseThrow());
        assertEquals(488.9948F % 360.0F,
                normalized.getListOrEmpty("Rotation").getFloatOr(0, Float.NaN));
        assertEquals("minecraft:attack_damage", normalized.getListOrEmpty("attributes")
                .getCompoundOrEmpty(0).getStringOr("id", ""));
    }

    private static CompoundTag entity(
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

    private static CompoundTag attribute(String id) {
        CompoundTag attribute = new CompoundTag();
        attribute.putString("id", id);
        return attribute;
    }

    private static ListTag strings(String first, String second) {
        ListTag list = new ListTag();
        list.add(StringTag.valueOf(first));
        list.add(StringTag.valueOf(second));
        return list;
    }
}
