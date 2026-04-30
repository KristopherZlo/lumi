package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.List;

public record PreparedChunkApplyBatch(
        ChunkPoint chunk,
        List<PreparedSectionApplyBatch> nativeSections,
        List<PreparedBlockPlacement> placements,
        EntityBatch entityBatch
) {

    public PreparedChunkApplyBatch {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk is required");
        }
        nativeSections = nativeSections == null ? List.of() : List.copyOf(nativeSections);
        placements = placements == null ? List.of() : List.copyOf(placements);
        entityBatch = entityBatch == null ? EntityBatch.empty() : entityBatch;
    }
}
