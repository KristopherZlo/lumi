package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.SectionBlob;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ThreeWayMergeTest {
    @Test
    void replayRestoresOnlyChangedCellsAndPreservesDisjointNbt() {
        var nbt = new CanonicalNbt(new byte[] {1});
        var expected = section("minecraft:gold_block", "minecraft:stone");
        var current = new SectionBlob(
                section("minecraft:gold_block", "minecraft:barrel").blockStates(), Map.of(1, nbt));
        var replacement = section("minecraft:dirt", "minecraft:stone");
        var replay = ThreeWayMerge.forActionReplay();

        var restored = replay.sections(expected, current, replacement).value();

        assertEquals("minecraft:dirt", restored.blockStates().get(0));
        assertEquals("minecraft:barrel", restored.blockStates().get(1));
        assertEquals(Map.of(1, nbt), restored.blockEntities());
        assertEquals(current, replay.sections(replacement, restored, expected).value());
    }

    @Test
    void replayRefusesChangedExpectedStateEvenWhenItAlreadyEqualsTheReplacement() {
        var expected = section("minecraft:gold_block");
        var replacement = section("minecraft:stone");
        var replay = ThreeWayMerge.forActionReplay();

        assertThrows(IllegalStateException.class, () -> replay.sections(
                expected, replacement, replacement));
        var modifiedNbt = new SectionBlob(expected.blockStates(),
                Map.of(0, new CanonicalNbt(new byte[] {2})));
        assertThrows(IllegalStateException.class, () -> replay.sections(
                expected, modifiedNbt, replacement));
    }

    @Test
    void replayMovesOneEntityAcrossChunksWithoutRemovingLaterDisjointEntities() {
        UUID moved = new UUID(0, 8);
        UUID later = new UUID(0, 9);
        var origin = new EntityChunkKey(0, 0);
        var target = new EntityChunkKey(1, 0);
        var expected = Map.of(origin, entities(entity(moved, 0)), target, entities());
        var current = Map.of(origin, entities(entity(moved, 0), entity(later, 1)), target, entities());
        var replacement = Map.of(origin, entities(), target, entities(entity(moved, 2)));
        var replay = ThreeWayMerge.forActionReplay();

        var result = replay.entityChunks(expected, current, replacement).value();

        assertEquals(List.of(entity(later, 1)), result.get(origin).entities());
        assertEquals(List.of(entity(moved, 2)), result.get(target).entities());
        assertEquals(current, replay.entityChunks(replacement, result, expected).value());
        var conflict = Map.of(origin, entities(entity(moved, 3)), target, entities());
        assertThrows(IllegalStateException.class,
                () -> replay.entityChunks(expected, conflict, replacement));
    }

    @Test
    void combinesIndependentCellsAndUsesSourceForConflict() {
        SectionBlob base = section("minecraft:air", "minecraft:air", "minecraft:air");
        SectionBlob current = section("minecraft:stone", "minecraft:air", "minecraft:dirt");
        SectionBlob source = section("minecraft:air", "minecraft:gold_block", "minecraft:diamond_block");

        var merged = new ThreeWayMerge().sections(base, current, source);

        assertEquals("minecraft:stone", merged.value().blockStates().get(0));
        assertEquals("minecraft:gold_block", merged.value().blockStates().get(1));
        assertEquals("minecraft:diamond_block", merged.value().blockStates().get(2));
        assertEquals(1, merged.conflicts());
        assertEquals(2, merged.changedBlocks());
    }

    @Test
    void combinesEntitiesByUuidAndUsesSourceForConflict() {
        UUID currentOnly = new UUID(0, 1);
        UUID sourceOnly = new UUID(0, 2);
        UUID conflict = new UUID(0, 3);
        EntityChunkBlob base = entities(entity(conflict, 0));
        EntityChunkBlob current = entities(entity(currentOnly, 1), entity(conflict, 1));
        EntityChunkBlob source = entities(entity(sourceOnly, 2), entity(conflict, 2));

        var merged = new ThreeWayMerge().entities(base, current, source);

        assertEquals(List.of(currentOnly, sourceOnly, conflict),
                merged.value().entities().stream().map(EntityState::id).toList());
        assertEquals(entity(conflict, 2), merged.value().entities().get(2));
        assertEquals(1, merged.conflicts());
        assertEquals(2, merged.changedEntities());
    }

    @Test
    void conflictingCrossChunkMoveKeepsOnlySourcePlacement() {
        UUID entity = new UUID(0, 4);
        EntityChunkKey origin = new EntityChunkKey(0, 0);
        EntityChunkKey currentChunk = new EntityChunkKey(1, 0);
        EntityChunkKey sourceChunk = new EntityChunkKey(2, 0);
        EntityChunkBlob empty = entities();

        var merged = new ThreeWayMerge().entityChunks(
                Map.of(origin, entities(entity(entity, 0)), currentChunk, empty, sourceChunk, empty),
                Map.of(origin, empty, currentChunk, entities(entity(entity, 1)), sourceChunk, empty),
                Map.of(origin, empty, currentChunk, empty, sourceChunk, entities(entity(entity, 2))));

        assertEquals(List.of(), merged.value().get(currentChunk).entities());
        assertEquals(List.of(entity(entity, 2)), merged.value().get(sourceChunk).entities());
        assertEquals(1, merged.conflicts());
        assertEquals(1, merged.changedEntities());
    }

    private static SectionBlob section(String... firstStates) {
        List<String> states = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air"));
        for (int index = 0; index < firstStates.length; index++) {
            states.set(index, firstStates[index]);
        }
        return new SectionBlob(states, Map.of());
    }

    private static EntityChunkBlob entities(EntityState... entities) {
        return new EntityChunkBlob(List.of(entities));
    }

    private static EntityState entity(UUID id, int state) {
        return new EntityState(id, "minecraft:armor_stand",
                new CanonicalNbt(new byte[] {(byte) state}));
    }
}
