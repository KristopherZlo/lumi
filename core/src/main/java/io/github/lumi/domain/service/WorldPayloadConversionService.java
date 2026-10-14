package io.github.lumi.domain.service;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.WorldDataVersion;
import java.io.IOException;
import java.util.Objects;

/** Applies the compatibility policy before delegating vanilla representation changes. */
public final class WorldPayloadConversionService {
    private final WorldDataVersion target;
    private final WorldPayloadConverter converter;

    public WorldPayloadConversionService(WorldDataVersion target, WorldPayloadConverter converter) {
        this.target = Objects.requireNonNull(target, "target");
        this.converter = Objects.requireNonNull(converter, "converter");
    }

    public SectionBlob convert(SectionBlob source) throws IOException {
        WorldDataCompatibility.requireForward(source.dataVersion(), target);
        SectionBlob result = converter.convert(source, target);
        WorldDataCompatibility.requireCurrent(result.dataVersion(), target);
        return result;
    }

    public EntityChunkBlob convert(EntityChunkBlob source) throws IOException {
        WorldDataCompatibility.requireForward(source.dataVersion(), target);
        EntityChunkBlob result = converter.convert(source, target);
        WorldDataCompatibility.requireCurrent(result.dataVersion(), target);
        return result;
    }
}
