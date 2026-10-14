package io.github.lumi.domain.model;

public sealed interface HistoryKey permits SectionKey, EntityChunkKey {
    int chunkX();

    int chunkZ();
}
