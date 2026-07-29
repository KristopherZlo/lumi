package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftEntityChunkCaptureTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void mapsNegativeChunkCoordinatesWithoutRepackingLoss() {
        assertEquals(new EntityChunkKey(-7, 11),
                MinecraftEntityChunkCapture.key(-7, 11));
    }

    @Test
    void canonicalEntityNbtLeavesIdentityOutsidePayloadAndNormalizesReloadFields()
            throws Exception {
        CompoundTag saved = new CompoundTag();
        saved.putString("id", "minecraft:armor_stand");
        saved.putIntArray("UUID", new int[] {0, 0, 0, 1});
        saved.putString("CustomName", "builder marker");
        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(488.9948F));
        rotation.add(FloatTag.valueOf(15.0F));
        saved.put("Rotation", rotation);
        ListTag attributes = new ListTag();
        CompoundTag movement = new CompoundTag();
        movement.putString("id", "minecraft:movement_speed");
        CompoundTag attack = new CompoundTag();
        attack.putString("id", "minecraft:attack_damage");
        attributes.add(movement);
        attributes.add(attack);
        saved.put("attributes", attributes);

        CompoundTag canonical = MinecraftNbtCodec.decode(
                new MinecraftEntityChunkCapture().canonicalEntityNbt(saved));

        assertFalse(canonical.contains("id"));
        assertFalse(canonical.contains("UUID"));
        assertEquals("builder marker", canonical.getStringOr("CustomName", ""));
        assertEquals(488.9948F % 360.0F,
                canonical.getListOrEmpty("Rotation").getFloatOr(0, Float.NaN));
        assertEquals("minecraft:attack_damage", canonical.getListOrEmpty("attributes")
                .getCompoundOrEmpty(0).getStringOr("id", ""));
        assertTrue(saved.contains("id"));
        assertTrue(saved.contains("UUID"));
    }

    @Test
    void capturesPhysicallyStoredEntityChunkWithoutMaterializingEntities() throws Exception {
        EntityChunkKey key = new EntityChunkKey(-4, 9);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000001");
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:armor_stand");
        entity.store("UUID", UUIDUtil.CODEC, id);
        entity.putString("CustomName", "stored marker");
        ListTag entities = new ListTag();
        entities.add(entity);
        CompoundTag root = storedRoot(key, entities);

        EntityState captured = new MinecraftEntityChunkCapture()
                .captureStored(key, Optional.of(root)).entities().getFirst();

        assertEquals(id, captured.id());
        assertEquals("minecraft:armor_stand", captured.type());
        assertEquals("stored marker", MinecraftNbtCodec.decode(captured.nbt())
                .getStringOr("CustomName", ""));
    }

    @Test
    void rejectsMalformedPhysicallyStoredEntityChunk() {
        EntityChunkKey key = new EntityChunkKey(2, 3);
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:armor_stand");
        ListTag entities = new ListTag();
        entities.add(entity);

        assertThrows(IOException.class, () -> new MinecraftEntityChunkCapture()
                .captureStored(key, Optional.of(storedRoot(key, entities))));
    }

    @Test
    void storedCleanupTagRoundTripsThroughTheVanillaShape() throws Exception {
        EntityChunkKey key = new EntityChunkKey(-3, 7);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000002");
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:armor_stand");
        entity.store("UUID", UUIDUtil.CODEC, id);
        entity.putString("CustomName", "cleanup marker");
        MinecraftEntityChunkCapture capture = new MinecraftEntityChunkCapture();
        EntityState state = new EntityState(
                id, "minecraft:armor_stand", capture.canonicalEntityNbt(entity));
        var decoded = new DecodedEntityChunk(List.of(
                new DecodedEntity(state, EntityType.ARMOR_STAND, entity)));

        CompoundTag stored = MinecraftStoredChunkAccess.entityTag(key, decoded);

        assertEquals(new EntityChunkBlob(List.of(state)),
                capture.captureStored(key, Optional.of(stored)));
    }

    private static CompoundTag storedRoot(EntityChunkKey key, ListTag entities) {
        CompoundTag root = new CompoundTag();
        root.store("Position", ChunkPos.CODEC, new ChunkPos(key.chunkX(), key.chunkZ()));
        root.put("Entities", entities);
        return root;
    }
}
