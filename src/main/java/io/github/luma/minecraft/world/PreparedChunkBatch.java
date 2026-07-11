package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.List;

public record PreparedChunkBatch(
        ChunkPoint chunk,
        List<PreparedBlockPlacement> placements,
        List<PreparedSectionApplyBatch> nativeSections,
        EntityBatch entityBatch
) {

    public PreparedChunkBatch(ChunkPoint chunk, List<PreparedBlockPlacement> placements) {
        this(chunk, placements, List.of(), EntityBatch.empty());
    }

    public PreparedChunkBatch(ChunkPoint chunk, List<PreparedBlockPlacement> placements, EntityBatch entityBatch) {
        this(chunk, placements, List.of(), entityBatch);
    }

    public PreparedChunkBatch {
        placements = placements == null ? List.of() : List.copyOf(placements);
        nativeSections = nativeSections == null ? List.of() : List.copyOf(nativeSections);
        entityBatch = entityBatch == null ? EntityBatch.empty() : entityBatch;
    }
}
