package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.List;

public record PreparedSectionApplyBatch(
        ChunkPoint chunk,
        int sectionY,
        LumiSectionBuffer buffer,
        SectionApplySafetyProfile safetyProfile,
        boolean fullSection
) {

    public PreparedSectionApplyBatch {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk is required");
        }
        if (buffer == null) {
            throw new IllegalArgumentException("buffer is required");
        }
        if (buffer.sectionY() != sectionY) {
            throw new IllegalArgumentException("buffer section does not match batch section");
        }
        safetyProfile = safetyProfile == null
                ? SectionApplySafetyProfile.directSection("unspecified")
                : safetyProfile;
    }

    public int changedCellCount() {
        return this.buffer.changedCellCount();
    }

    SectionBatch toSectionBatch() {
        List<PreparedBlockPlacement> placements = this.buffer.toPlacements(this.chunk);
        return new SectionBatch(this.sectionY, this.buffer.changedCells().toBitSet(), placements);
    }
}
