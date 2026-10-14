package io.github.lumi.domain.model;

public record SectionKey(int chunkX, int sectionY, int chunkZ) implements HistoryKey {
}
