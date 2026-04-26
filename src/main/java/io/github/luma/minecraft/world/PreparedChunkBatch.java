package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.List;

public record PreparedChunkBatch(
        ChunkPoint chunk,
        List<PreparedBlockPlacement> placements,
        EntityBatch entityBatch
) {

    public PreparedChunkBatch(ChunkPoint chunk, List<PreparedBlockPlacement> placements) {
        this(chunk, placements, EntityBatch.empty());
    }

    public PreparedChunkBatch {
        placements = placements == null ? List.of() : List.copyOf(placements);
        entityBatch = entityBatch == null ? EntityBatch.empty() : entityBatch;
    }
}
