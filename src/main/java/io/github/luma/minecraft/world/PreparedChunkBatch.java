package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.List;

public record PreparedChunkBatch(
        ChunkPoint chunk,
        List<PreparedBlockPlacement> placements
) {
}
