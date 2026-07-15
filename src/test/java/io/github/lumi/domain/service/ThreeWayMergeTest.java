package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
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
