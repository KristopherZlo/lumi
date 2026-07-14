package io.github.luma.domain.service;

import io.github.luma.domain.model.ProjectIntegrityReport;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectIntegrityRepository;
import java.io.IOException;
import net.minecraft.server.MinecraftServer;

public final class ProjectIntegrityService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectIntegrityRepository integrityRepository = new ProjectIntegrityRepository();
    private final HistoryProtectionService historyProtectionService = new HistoryProtectionService();

    public ProjectIntegrityReport inspect(MinecraftServer server, String projectName) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        return this.inspect(layout);
    }

    ProjectIntegrityReport inspect(ProjectLayout layout) throws IOException {
        ProjectIntegrityReport report = this.integrityRepository.inspect(layout);
        if (!report.valid()) {
            String detail = report.errors().isEmpty() ? "Project integrity check failed" : report.errors().getFirst();
            try {
                this.historyProtectionService.markDegraded(layout, detail);
            } catch (IOException markerFailure) {
                io.github.luma.LumaMod.LOGGER.error(
                        "Failed to persist degraded state for {}",
                        layout.root(),
                        markerFailure
                );
            }
        }
        return report;
    }
}
