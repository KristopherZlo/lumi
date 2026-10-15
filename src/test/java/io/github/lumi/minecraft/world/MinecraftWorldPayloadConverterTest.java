package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.WorldDataVersion;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftWorldPayloadConverterTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        //? if >=26.1 {
        /*BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(net.minecraft.data.registries.VanillaRegistries.createLookup())
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        *///?}
    }

    @Test
    void canonicalizesTheTargetPaletteAndPreservesEntityIdentityWithoutChangingSource() throws Exception {
        var converter = converter();
        var source = new SectionBlob(Collections.nCopies(4096, "minecraft:oak_stairs[facing=west]"), Map.of());
        SectionBlob result = converter.convert(source, NativeWorldTestData.version());
        assertEquals("minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]",
                result.blockStates().getFirst());
        assertEquals("minecraft:oak_stairs[facing=west]", source.blockStates().getFirst());
        assertEquals(result, converter.convert(result, NativeWorldTestData.version()));
        UUID id = UUID.randomUUID();
        var entities = new EntityChunkBlob(List.of(new EntityState(id, "minecraft:armor_stand",
                MinecraftNbtCodec.encode(new CompoundTag()))));
        assertEquals(id, converter.convert(entities, NativeWorldTestData.version()).entities().getFirst().id());
    }

    @Test
    void chunkContextDropsOnlyVanillaRemovedBlockEntities() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(NativeWorldTestData.version().value() >= 4885);
        var bed = new CompoundTag();
        bed.putString("id", "minecraft:bed");
        var source = new SectionBlob(Collections.nCopies(4096, "minecraft:red_bed"),
                Map.of(0, MinecraftNbtCodec.encode(bed)));
        SectionBlob result = converter().convert(source, NativeWorldTestData.version());
        assertEquals(Map.of(), result.blockEntities());
        assertEquals(1, source.blockEntities().size());
        assertEquals("minecraft:red_bed[facing=north,occupied=false,part=foot]", result.blockStates().getFirst());
    }

    @Test
    void convertedFurnaceUsesTheRunningGamesSerializedDefaults() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(NativeWorldTestData.version().value() >= 5000);
        var furnace = new CompoundTag();
        furnace.putString("id", "minecraft:furnace");
        furnace.putShort("cooking_time_spent", (short) 17);
        furnace.putShort("cooking_total_time", (short) 200);
        var source = new SectionBlob(Collections.nCopies(4096, "minecraft:furnace"),
                Map.of(0, MinecraftNbtCodec.encode(furnace)));
        SectionBlob converted = converter().convert(source, NativeWorldTestData.version());
        var tag = MinecraftNbtCodec.decode(converted.blockEntities().get(0));
        assertEquals(net.minecraft.nbt.Tag.TAG_INT, tag.get("cooking_time_spent").getId());
        assertEquals(17, tag.getIntOr("cooking_time_spent", -1));
        assertEquals(1.0f, tag.getFloatOr("speed_multiplier", -1));
        assertEquals(converted, converter().convert(converted, NativeWorldTestData.version()));
        assertEquals(net.minecraft.nbt.Tag.TAG_SHORT,
                MinecraftNbtCodec.decode(source.blockEntities().get(0)).get("cooking_time_spent").getId());
    }

    @Test
    void chunkContextRejectsUnknownBlockEntityTypes() throws Exception {
        var unknown = new CompoundTag();
        unknown.putString("id", "missing:block_entity");
        var source = new SectionBlob(Collections.nCopies(4096, "minecraft:stone"),
                Map.of(0, MinecraftNbtCodec.encode(unknown)));
        assertThrows(IOException.class, () -> converter().convert(source, NativeWorldTestData.version()));
    }

    @Test
    void refusesMissingRegistryTypesAndATargetOtherThanTheRunningGame() {
        var converter = converter();
        var source = new SectionBlob(Collections.nCopies(4096, "missing:block"), Map.of());
        assertThrows(IOException.class, () -> converter.convert(source, NativeWorldTestData.version()));
        var entities = new EntityChunkBlob(List.of(new EntityState(UUID.randomUUID(), "missing:entity",
                new io.github.lumi.domain.model.CanonicalNbt(new byte[] {10, 0, 0, 0}))));
        assertThrows(IOException.class, () -> converter.convert(entities, NativeWorldTestData.version()));
        assertThrows(IOException.class, () -> converter.convert(source, new WorldDataVersion(NativeWorldTestData.version().value() == 4671 ? 4786 : 4671)));
    }

    @Test
    void refusesMissingInventoryItemsAndAttributesButPreservesOpaqueCustomData() throws Exception {
        var converter = converter();
        var chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        var item = new CompoundTag();
        item.putString("id", "missing:item");
        item.putInt("count", 1);
        var inventory = new net.minecraft.nbt.ListTag();
        inventory.add(item);
        chest.put("Items", inventory);
        var section = NativeWorldTestData.section(Collections.nCopies(4096, "minecraft:chest"),
                Map.of(0, MinecraftNbtCodec.encode(chest)));
        assertThrows(IOException.class, () -> converter.convert(section, NativeWorldTestData.version()));
        var attribute = new CompoundTag();
        attribute.putString("id", "missing:attribute");
        var attributes = new net.minecraft.nbt.ListTag();
        attributes.add(attribute);
        var payload = new CompoundTag();
        payload.put("attributes", attributes);
        var entities = new EntityChunkBlob(List.of(new EntityState(UUID.randomUUID(), "minecraft:armor_stand",
                MinecraftNbtCodec.encode(payload))));
        assertThrows(IOException.class, () -> converter.convert(entities, NativeWorldTestData.version()));
        var opaque = new CompoundTag();
        opaque.put("Items", inventory);
        chest.remove("Items");
        chest.put("minecraft:custom_data", opaque);
        var safe = NativeWorldTestData.section(Collections.nCopies(4096, "minecraft:chest"),
                Map.of(0, MinecraftNbtCodec.encode(chest)));
        assertEquals(safe.blockEntities(), converter.convert(safe, NativeWorldTestData.version()).blockEntities());
    }

    @Test
    void rejectsMissingAndMalformedItemComponentsWithoutAcceptingPartialDecodes() throws Exception {
        var components = new CompoundTag();
        components.put("missing:component", new CompoundTag());
        assertThrows(IOException.class, () -> converter().convert(chestWith(components), NativeWorldTestData.version()));
        components.remove("missing:component");
        components.putString("minecraft:damage", "invalid");
        assertThrows(IOException.class, () -> converter().convert(chestWith(components), NativeWorldTestData.version()));
        components.remove("minecraft:damage");
        var custom = new CompoundTag();
        custom.putString("item", "opaque custom value");
        components.put("minecraft:custom_data", custom);
        SectionBlob valid = chestWith(components);
        assertEquals(valid.blockEntities(), converter().convert(valid, NativeWorldTestData.version()).blockEntities());
    }

    private static SectionBlob chestWith(CompoundTag components) throws IOException {
        var item = new CompoundTag();
        item.putString("id", "minecraft:diamond");
        item.putInt("count", 1);
        item.put("components", components);
        var items = new net.minecraft.nbt.ListTag();
        items.add(item);
        var chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.put("Items", items);
        return NativeWorldTestData.section(Collections.nCopies(4096, "minecraft:chest"), Map.of(0, MinecraftNbtCodec.encode(chest)));
    }

    private static MinecraftWorldPayloadConverter converter() {
        return new MinecraftWorldPayloadConverter(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
                BuiltInRegistries.ENTITY_TYPE);
    }
}
