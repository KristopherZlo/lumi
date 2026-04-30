package io.github.luma.minecraft.world;

import java.util.List;

public record PreparedWorldApplyPlan(
        List<PreparedChunkApplyBatch> chunks
) {

    public PreparedWorldApplyPlan {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
