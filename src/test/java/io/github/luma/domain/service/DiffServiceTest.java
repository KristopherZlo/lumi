package io.github.luma.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiffServiceTest {

    private final DiffService diffService = new DiffService();

    @Test
    void extractBlockIdReadsStateName() {
        String blockId = this.diffService.extractBlockId("{Name:\"minecraft:stone\",Properties:{axis:\"y\"}}");

        assertEquals("minecraft:stone", blockId);
    }

    @Test
    void extractBlockIdFallsBackForBlankAndUnknown() {
        assertEquals("minecraft:air", this.diffService.extractBlockId(""));
        assertEquals("minecraft:unknown", this.diffService.extractBlockId("{foo:\"bar\"}"));
    }
}
