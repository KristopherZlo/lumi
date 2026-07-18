package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftRestorePreparationTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void preservesCoordinatesWhileReplacingPersistentPayloadTypes() throws Exception {
        SectionKey sectionKey = new SectionKey(-1, 2, 3);
        EntityChunkKey entityKey = new EntityChunkKey(-1, 3);
        var source = new WorldStateApply.State(
                Map.of(sectionKey, new SectionBlob(new ArrayList<>(Collections.nCopies(
                        SectionBlob.BLOCK_COUNT, "minecraft:stone")), Map.of())),
                Map.of(entityKey, new EntityChunkBlob(List.of())));
        var preparation = new MinecraftRestorePreparation(
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE));

        PreparedMinecraftState prepared = preparation.prepare(source);

        assertEquals(source, prepared.source());
        assertEquals(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                prepared.sections().get(sectionKey).blockStates().getFirst());
        assertEquals(List.of(), prepared.entities().get(entityKey).entities());
    }

    @Test
    void preparesLegacyEntityNbtInItsReloadStableForm() throws Exception {
        EntityChunkKey key = new EntityChunkKey(2, 3);
        UUID id = UUID.fromString("30000000-0000-0000-0000-000000000003");
        EntityChunkBlob legacy = entityChunk(id, 488.9948F, -1285.8915F,
                "minecraft:movement_speed", "minecraft:attack_damage");
        EntityChunkBlob runtime = entityChunk(id, 488.9948F % 360.0F, -90.0F,
                "minecraft:attack_damage", "minecraft:movement_speed");
        var source = new WorldStateApply.State(Map.of(), Map.of(key, legacy));
        var preparation = new MinecraftRestorePreparation(
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE));

        PreparedMinecraftState prepared = preparation.prepare(source);

        assertNotEquals(legacy, runtime);
        assertEquals(runtime, prepared.source().entities().get(key));
    }

    private static EntityChunkBlob entityChunk(
            UUID id, float yaw, float pitch, String firstAttribute, String secondAttribute)
            throws Exception {
        CompoundTag entity = new CompoundTag();
        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(yaw));
        rotation.add(FloatTag.valueOf(pitch));
        entity.put("Rotation", rotation);
        ListTag attributes = new ListTag();
        attributes.add(attribute(firstAttribute));
        attributes.add(attribute(secondAttribute));
        entity.put("attributes", attributes);
        return new EntityChunkBlob(List.of(new EntityState(
                id, "minecraft:bat", MinecraftNbtCodec.encode(entity))));
    }

    private static CompoundTag attribute(String id) {
        CompoundTag attribute = new CompoundTag();
        attribute.putString("id", id);
        return attribute;
    }
}
