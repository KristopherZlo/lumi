package io.github.luma.ui.state;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.WorkZone;
import java.util.List;

public record VariantsViewState(
        BuildProject project,
        List<ProjectVersion> versions,
        List<ProjectVariant> variants,
        WorkZone activeZone,
        OperationSnapshot operationSnapshot,
        String status
) {
}
