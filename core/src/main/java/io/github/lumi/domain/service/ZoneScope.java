package io.github.lumi.domain.service;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.Zone;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Precomputed exact cell and entity-column membership for one zone operation. */
public final class ZoneScope {
    private final Set<SectionKey> cells;
    private final Set<Long> entityColumns;

    public ZoneScope(Zone zone) {
        Objects.requireNonNull(zone, "zone");
        cells = zone.cells();
        var columns = new HashSet<Long>();
        for (SectionKey cell : cells) {
            columns.add(column(cell.chunkX(), cell.chunkZ()));
        }
        entityColumns = Set.copyOf(columns);
    }

    public boolean includes(HistoryKey key) {
        Objects.requireNonNull(key, "key");
        return key instanceof SectionKey section
                ? cells.contains(section)
                : entityColumns.contains(column(
                        ((EntityChunkKey) key).chunkX(), ((EntityChunkKey) key).chunkZ()));
    }

    private static long column(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }
}
