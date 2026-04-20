package io.github.luma.ui.state;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectVersion;
import java.util.List;

public record ProjectViewState(
        BuildProject project,
        List<ProjectVersion> versions,
        String status
) {
}
