package io.github.lumi.domain.service;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.WorldDataVersion;
import java.io.IOException;

/** Worker-thread conversion with target-registry validation and no live world mutation. */
public interface WorldPayloadConverter {
    SectionBlob convert(SectionBlob source, WorldDataVersion target) throws IOException;
    EntityChunkBlob convert(EntityChunkBlob source, WorldDataVersion target) throws IOException;
}
