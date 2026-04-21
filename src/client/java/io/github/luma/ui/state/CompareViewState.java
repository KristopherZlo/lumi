package io.github.luma.ui.state;

import io.github.luma.domain.model.MaterialDeltaEntry;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionDiff;
import java.util.List;

public record CompareViewState(
        List<ProjectVersion> versions,
        List<ProjectVariant> variants,
        String activeVariantId,
        String leftReference,
        String rightReference,
        String leftResolvedVersionId,
        String rightResolvedVersionId,
        VersionDiff diff,
        List<MaterialDeltaEntry> materialDelta,
        String status,
        boolean debugEnabled
) {
}
