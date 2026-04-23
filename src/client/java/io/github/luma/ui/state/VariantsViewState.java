package io.github.luma.ui.state;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import java.util.List;

public record VariantsViewState(
        BuildProject project,
        List<ProjectVersion> versions,
        List<ProjectVariant> variants,
        OperationSnapshot operationSnapshot,
        String status
) {
}
