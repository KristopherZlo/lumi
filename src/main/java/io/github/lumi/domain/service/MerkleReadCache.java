package io.github.lumi.domain.service;

import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Reuses decoded Merkle nodes within one bounded domain workflow. */
final class MerkleReadCache {
    private final WorldObjectRepository.ReadSession reader;
    private final Map<ObjectId, RegionTree> regions = new HashMap<>();
    private final Map<ObjectId, ChunkTree> chunks = new HashMap<>();

    MerkleReadCache(WorldObjectRepository.ReadSession reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    RegionTree region(ObjectId id) throws IOException {
        RegionTree region = regions.get(id);
        if (region == null) {
            region = reader.readRegion(id);
            regions.put(id, region);
        }
        return region;
    }

    ChunkTree chunk(ObjectId id) throws IOException {
        ChunkTree chunk = chunks.get(id);
        if (chunk == null) {
            chunk = reader.readChunk(id);
            chunks.put(id, chunk);
        }
        return chunk;
    }
}
