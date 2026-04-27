package io.github.luma.ui.state;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.HistoryPackageFileSummary;
import io.github.luma.domain.model.ImportedHistoryProjectSummary;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import java.nio.file.Path;
import java.util.List;

public record ShareViewState(
        BuildProject project,
        List<ProjectVersion> versions,
        List<ProjectVariant> variants,
        List<ImportedHistoryProjectSummary> importedProjects,
        Path packageFolder,
        List<HistoryPackageFileSummary> packageFiles,
        OperationSnapshot operationSnapshot,
        String status
) {
}
