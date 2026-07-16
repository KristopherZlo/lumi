package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScopedSavePreparationTest {
    @Test
    void delegatesDurabilityThenReturnsOnlyIncludedGenerations() throws Exception {
        SectionKey included = new SectionKey(1, 2, 3);
        SectionKey excluded = new SectionKey(4, 5, 6);
        EntityChunkKey entities = new EntityChunkKey(1, 3);
        SavePreparation source = () -> new SavePreparation.Session() {
            private int calls;

            @Override
            public boolean prepareUntil(long deadlineNanos) {
                return ++calls == 2;
            }

            @Override
            public WorkingIndexSnapshot finish() {
                return new WorkingIndexSnapshot(Map.of(
                        included, 7L, excluded, 8L, entities, 9L));
            }
        };
        SavePreparation.Session session = new ScopedSavePreparation(
                source, key -> key.equals(included) || key.equals(entities)).begin();

        assertFalse(session.prepareUntil(1));
        assertTrue(session.prepareUntil(2));
        assertEquals(Map.of(included, 7L, entities, 9L), session.finish().generations());
    }
}
