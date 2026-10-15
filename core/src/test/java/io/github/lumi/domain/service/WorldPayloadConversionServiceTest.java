package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.WorldDataVersion;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldPayloadConversionServiceTest {
    private final WorldDataVersion modern = new WorldDataVersion(4786);

    @Test
    void rejectsUnsupportedAndBackwardRoutesBeforeCallingTheAdapter() {
        var adapter = new RecordingConverter();
        var service = new WorldPayloadConversionService(WorldDataVersion.LEGACY_12111, adapter);
        assertThrows(IOException.class, () -> service.convert(new EntityChunkBlob(List.of(), modern)));
        assertThrows(IOException.class, () -> service.convert(new EntityChunkBlob(List.of(), new WorldDataVersion(9000))));
        assertEquals(0, adapter.calls);
    }

    @Test
    void validatesSameVersionPayloadsAndRequiresTheAdapterToProduceTheTargetVersion() throws Exception {
        var adapter = new RecordingConverter();
        var service = new WorldPayloadConversionService(modern, adapter);
        assertEquals(modern, service.convert(new EntityChunkBlob(List.of())).dataVersion());
        SectionBlob section = new SectionBlob(Collections.nCopies(4096, "minecraft:air"), Map.of(), modern);
        assertEquals(modern, service.convert(section).dataVersion());
        assertEquals(2, adapter.calls);
        adapter.output = WorldDataVersion.LEGACY_12111;
        assertThrows(IOException.class, () -> service.convert(section));
        assertThrows(IOException.class, () -> WorldDataCompatibility.requireCurrent(
                WorldDataVersion.LEGACY_12111, modern));
    }

    private static final class RecordingConverter implements WorldPayloadConverter {
        @Override public String recipeId() { return "test-converter-v1"; }
        private int calls;
        private WorldDataVersion output;

        @Override
        public SectionBlob convert(SectionBlob source, WorldDataVersion target) {
            calls++;
            return new SectionBlob(source.blockStates(), source.blockEntities(), output == null ? target : output);
        }

        @Override
        public EntityChunkBlob convert(EntityChunkBlob source, WorldDataVersion target) {
            calls++;
            return new EntityChunkBlob(source.entities(), output == null ? target : output);
        }
    }
}
