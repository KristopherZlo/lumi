package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;

/** Reads one decoded world payload while its dimension is frozen. */
public interface WorldStateReader {
    SectionBlob read(SectionKey key) throws IOException;

    EntityChunkBlob read(EntityChunkKey key) throws IOException;
}
