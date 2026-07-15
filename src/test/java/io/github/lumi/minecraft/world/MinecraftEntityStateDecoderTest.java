package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityState;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
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
}
