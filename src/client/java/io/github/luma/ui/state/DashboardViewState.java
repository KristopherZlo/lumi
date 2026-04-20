package io.github.luma.ui.state;

import java.util.List;

public record DashboardViewState(
        List<DashboardProjectItem> projects,
        String status
) {
}
