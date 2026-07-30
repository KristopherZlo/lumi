package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
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
        List<Long> progress = new ArrayList<>();

        PreparedMinecraftState prepared = preparation.prepare(source, progress::add);

        assertEquals(source, prepared.source());
        assertEquals(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                prepared.sections().get(sectionKey).blockStates().getFirst());
        assertEquals(List.of(), prepared.entities().get(entityKey).entities());
        assertEquals(List.of(1L, 2L), progress);
    }

    @Test
    void parsesEachPersistentBlockStateOnlyOnceAcrossRestoreSections() throws Exception {
        var decoder = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK);
        var states = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone"));
        states.set(0, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        SectionBlob section = new SectionBlob(states, Map.of());

        decoder.decode(section);
        decoder.decode(section);

        assertEquals(2, decoder.cachedStateCount());
    }

    @Test
    void precomputesExactBlockAndLightDeltaAgainstReturnState() throws Exception {
        SectionKey key = new SectionKey(2, 1, -3);
        var beforeStates = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone"));
        var targetStates = new ArrayList<>(beforeStates);
        targetStates.set(17, "minecraft:glowstone");
        targetStates.set(18, "minecraft:lectern[facing=north,has_book=false,powered=false]");
        var before = new WorldStateApply.State(
                Map.of(key, new SectionBlob(beforeStates, Map.of())), Map.of());
        var target = new WorldStateApply.State(
                Map.of(key, new SectionBlob(targetStates, Map.of())), Map.of());
        var preparation = new MinecraftRestorePreparation(
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE));

        PreparedSectionDelta delta = preparation.prepare(target, before, ignored -> { })
                .sections().get(key).deltaFrom(null);

        assertArrayEquals(new int[] {17, 18}, delta.changedIndexes());
        assertArrayEquals(new int[] {18}, delta.poiIndexes());
        assertEquals(2, delta.changedCells().length);
        assertTrue(delta.lightChanged());
        assertFalse(delta.blockEntitiesChanged());
    }

    @Test
    void relightsWhenShapeOcclusionChangesBetweenBlockStates() throws Exception {
        SectionKey key = new SectionKey(0, 0, 0);
        var beforeStates = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air"));
        var targetStates = new ArrayList<>(beforeStates);
        beforeStates.set(0,
                "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        targetStates.set(0,
                "minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]");
        var preparation = new MinecraftRestorePreparation(
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE));

        PreparedSectionDelta delta = preparation.prepare(
                        new WorldStateApply.State(Map.of(
                                key, new SectionBlob(targetStates, Map.of())), Map.of()),
                        new WorldStateApply.State(Map.of(
                                key, new SectionBlob(beforeStates, Map.of())), Map.of()),
                        ignored -> { })
                .sections().get(key).deltaFrom(null);

        assertTrue(delta.lightChanged());
    }

    @Test
    void reusesPreflightedTargetPayloadWithCoordinateSpecificDeltas() throws Exception {
        SectionKey firstKey = new SectionKey(0, 0, 0);
        SectionKey secondKey = new SectionKey(1, 0, 0);
        var targetStates = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone"));
        SectionBlob targetSection = new SectionBlob(targetStates, Map.of());
        var firstBaseStates = new ArrayList<>(targetStates);
        firstBaseStates.set(17, "minecraft:dirt");
        var secondBaseStates = new ArrayList<>(targetStates);
        secondBaseStates.set(23, "minecraft:dirt");
        List<SectionKey> order = List.of(secondKey, firstKey);
        var source = new WorldStateApply.State(
                Map.of(firstKey, targetSection, secondKey, targetSection), Map.of());
        var base = new WorldStateApply.State(Map.of(
                firstKey, new SectionBlob(firstBaseStates, Map.of()),
                secondKey, new SectionBlob(secondBaseStates, Map.of())), Map.of());
        var preparation = new MinecraftRestorePreparation(
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE));

        PreparedMinecraftState prepared = preparation.preparePreflightedBatch(
                source, base, order, () -> false);

        DecodedSection first = prepared.sections().get(firstKey);
        DecodedSection second = prepared.sections().get(secondKey);
        assertSame(first.blockStates(), second.blockStates());
        assertArrayEquals(new int[] {17}, first.preparedDelta().changedIndexes());
        assertArrayEquals(new int[] {23}, second.preparedDelta().changedIndexes());
        assertEquals(order, prepared.sectionKeys());
    }

    @Test
    void cancelsPreflightedPreparationBeforeDecodingLaterSection() {
        SectionKey firstKey = new SectionKey(0, 0, 0);
        SectionKey invalidKey = new SectionKey(1, 0, 0);
        var invalidStates = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air"));
        invalidStates.set(0, "missing:not_a_block");
        var source = new WorldStateApply.State(Map.of(
                firstKey, airSection(),
                invalidKey, new SectionBlob(invalidStates, Map.of())), Map.of());
        var base = new WorldStateApply.State(Map.of(
                firstKey, airSection(), invalidKey, airSection()), Map.of());
        AtomicInteger checks = new AtomicInteger();
        var preparation = new MinecraftRestorePreparation(
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE));

        assertThrows(CancellationException.class,
                () -> preparation.preparePreflightedBatch(
                        source, base, List.of(firstKey, invalidKey),
                        () -> checks.getAndIncrement() > 0));

        assertEquals(2, checks.get());
    }

    @Test
    void preflightsWithoutRetainingDecodedSectionPayloads() throws Exception {
        SectionKey key = new SectionKey(0, 0, 0);
        var states = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone"));
        var source = new WorldStateApply.State(
                Map.of(key, new SectionBlob(states, Map.of())), Map.of());
        var baseStates = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:dirt"));
        var base = new WorldStateApply.State(
                Map.of(key, new SectionBlob(baseStates, Map.of())), Map.of());
        var preparation = new MinecraftRestorePreparation(
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE));

        PreparedMinecraftPlanState plan = preparation.preflight(
                source, base, ignored -> { });

        assertEquals(List.of(key), plan.sectionKeys());
        assertEquals(source, plan.source());
        assertEquals(base, plan.reversed().source());
        assertEquals(Map.of(), plan.entities());
    }

    @Test
    void preflightsAllPlannedEntityIdentitiesForBothRestoreDirections() throws Exception {
        EntityChunkKey oldKey = new EntityChunkKey(1, 2);
        EntityChunkKey targetKey = new EntityChunkKey(2, 2);
        EntityChunkKey unchangedKey = new EntityChunkKey(3, 2);
        UUID id = UUID.fromString("40000000-0000-0000-0000-000000000004");
        UUID unchanged =
                UUID.fromString("50000000-0000-0000-0000-000000000005");
        EntityChunkBlob entity = entityChunk(id, 0.0F, 0.0F,
                "minecraft:movement_speed", "minecraft:attack_damage");
        EntityChunkBlob unchangedEntity = entityChunk(unchanged, 0.0F, 0.0F,
                "minecraft:movement_speed", "minecraft:attack_damage");
        EntityChunkBlob empty = new EntityChunkBlob(List.of());
        var target = new WorldStateApply.State(Map.of(), Map.of(
                oldKey, empty, targetKey, entity, unchangedKey, unchangedEntity));
        var base = new WorldStateApply.State(Map.of(), Map.of(
                oldKey, entity, targetKey, empty, unchangedKey, unchangedEntity));
        var preparation = new MinecraftRestorePreparation(
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE));

        PreparedMinecraftPlanState plan =
                preparation.preflight(target, base, ignored -> { });

        assertEquals(Set.of(id, unchanged), plan.cleanupEntityIds());
        assertEquals(plan.cleanupEntityIds(), plan.reversed().cleanupEntityIds());
    }

    @Test
    void reusesOnlyIdenticalRecentlyValidatedSectionsWithinTheBound() throws Exception {
        var decoder = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK);
        var validated = new MinecraftRestorePreparation.ValidatedSectionWindow(decoder);
        SectionBlob first = airSection();

        assertTrue(validated.validate(first));
        assertFalse(validated.validate(first));
        assertTrue(validated.validate(airSection()));

        List<SectionBlob> distinct = new ArrayList<>();
        for (int index = 0;
                index <= MinecraftRestorePreparation.MAX_RECENT_VALIDATIONS;
                index++) {
            SectionBlob section = airSection();
            distinct.add(section);
            validated.validate(section);
        }

        assertEquals(MinecraftRestorePreparation.MAX_RECENT_VALIDATIONS,
                validated.size());
        assertFalse(validated.tracks(first));
        assertTrue(validated.tracks(distinct.getLast()));
    }

    @Test
    void failedSectionValidationIsNeverReused() {
        var states = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air"));
        states.set(0, "missing:not_a_block");
        SectionBlob invalid = new SectionBlob(states, Map.of());
        var validated = new MinecraftRestorePreparation.ValidatedSectionWindow(
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK));

        assertThrows(IOException.class, () -> validated.validate(invalid));
        assertThrows(IOException.class, () -> validated.validate(invalid));
        assertEquals(0, validated.size());
        assertFalse(validated.tracks(invalid));
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

    private static SectionBlob airSection() {
        return new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air")), Map.of());
    }
}
