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
    }

    @Test
    void canonicalizesTheTargetPaletteAndPreservesEntityIdentityWithoutChangingSource() throws Exception {
        var converter = new MinecraftWorldPayloadConverter(BuiltInRegistries.BLOCK, BuiltInRegistries.ENTITY_TYPE);
        var source = new SectionBlob(Collections.nCopies(4096, "minecraft:oak_stairs[facing=west]"), Map.of());
        SectionBlob result = converter.convert(source, WorldDataVersion.LEGACY_12111);
        assertEquals("minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]",
                result.blockStates().getFirst());
        assertEquals("minecraft:oak_stairs[facing=west]", source.blockStates().getFirst());
        UUID id = UUID.randomUUID();
        var entities = new EntityChunkBlob(List.of(new EntityState(id, "minecraft:armor_stand",
                MinecraftNbtCodec.encode(new CompoundTag()))));
        assertEquals(id, converter.convert(entities, WorldDataVersion.LEGACY_12111).entities().getFirst().id());
    }

    @Test
    void refusesMissingRegistryTypesAndATargetOtherThanTheRunningGame() {
        var converter = new MinecraftWorldPayloadConverter(BuiltInRegistries.BLOCK, BuiltInRegistries.ENTITY_TYPE);
        var source = new SectionBlob(Collections.nCopies(4096, "missing:block"), Map.of());
        assertThrows(IOException.class, () -> converter.convert(source, WorldDataVersion.LEGACY_12111));
        var entities = new EntityChunkBlob(List.of(new EntityState(UUID.randomUUID(), "missing:entity",
                new io.github.lumi.domain.model.CanonicalNbt(new byte[] {10, 0, 0, 0}))));
        assertThrows(IOException.class, () -> converter.convert(entities, WorldDataVersion.LEGACY_12111));
        assertThrows(IOException.class, () -> converter.convert(source, new WorldDataVersion(4786)));
    }
}
