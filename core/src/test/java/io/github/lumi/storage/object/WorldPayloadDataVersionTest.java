package io.github.lumi.storage.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.domain.service.ThreeWayMerge;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldPayloadDataVersionTest {
    private final SectionBlobCodec sections = new SectionBlobCodec();
    private final EntityChunkBlobCodec entities = new EntityChunkBlobCodec();
    private final WorldDataVersion modern = new WorldDataVersion(4786);

    @Test
    void legacyObjectsRetainTheirOriginalCanonicalIdentities() throws Exception {
        byte[] section = sections.encode(section(WorldDataVersion.LEGACY_12111));
        byte[] chunk = entities.encode(new EntityChunkBlob(List.of()));
        assertEquals("13b4ca7208c87675fa11cd37d7c2414a8bd00e1bfce3cff735c61999ca9de58f",
                ObjectId.hash(section).hex());
        assertEquals("029994f23ec1fb518c47c4320921bf439ea255a3168a3060af97fa06652697ab",
                ObjectId.hash(chunk).hex());
        assertEquals(WorldDataVersion.LEGACY_12111, sections.decode(section).dataVersion());
        assertEquals(WorldDataVersion.LEGACY_12111, entities.decode(chunk).dataVersion());
    }

    @Test
    void modernLeavesCarryTheirOwnVersionAndRefuseMalformedVersions() throws Exception {
        byte[] section = sections.encode(section(modern));
        byte[] chunk = entities.encode(new EntityChunkBlob(List.of(), modern));
        assertEquals(modern, sections.decode(section).dataVersion());
        assertEquals(modern, entities.decode(chunk).dataVersion());
        ByteBuffer.wrap(section).putInt(4, 0);
        ByteBuffer.wrap(chunk).putInt(4, -1);
        assertThrows(IOException.class, () -> sections.decode(section));
        assertThrows(IOException.class, () -> entities.decode(chunk));
    }

    @Test
    void mergingRequiresConversionAndPreservesTheTargetRepresentation() {
        var merge = new ThreeWayMerge();
        SectionBlob modernSection = section(modern);
        assertEquals(modern, merge.sections(modernSection, modernSection, modernSection).value().dataVersion());
        assertThrows(IllegalArgumentException.class, () -> merge.sections(
                section(WorldDataVersion.LEGACY_12111), modernSection, modernSection));
        EntityChunkBlob chunk = new EntityChunkBlob(List.of(), modern);
        assertEquals(modern, merge.entities(chunk, chunk, chunk).value().dataVersion());
        assertThrows(IllegalArgumentException.class,
                () -> merge.entities(new EntityChunkBlob(List.of()), chunk, chunk));
    }

    private static SectionBlob section(WorldDataVersion version) {
        return new SectionBlob(Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:air"), Map.of(), version);
    }
}
