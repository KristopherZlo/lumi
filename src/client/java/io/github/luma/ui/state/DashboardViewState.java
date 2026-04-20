package io.github.luma.ui.state;

import io.github.luma.domain.model.BuildProject;
import java.util.List;

public record DashboardViewState(
        List<BuildProject> projects,
        String status
) {
}
