package io.github.luma.ui.controller;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectIntegrityReport;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.service.ChangeStatsFactory;
import io.github.luma.domain.service.ProjectIntegrityService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.integration.common.IntegrationStatus;
import io.github.luma.integration.common.IntegrationStatusService;
import io.github.luma.ui.state.ProjectAdvancedViewState;
import io.github.luma.ui.state.ProjectHomeViewState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

public final class ProjectHomeScreenController {

    private final Query query;

    public ProjectHomeScreenController() {
        this(new ServiceQuery());
    }

    ProjectHomeScreenController(Query query) {
        this.query = query;
    }

    public ProjectHomeViewState loadState(String projectName, String status, boolean includeAdvanced) {
        if (!this.query.hasSingleplayerServer()) {
            return new ProjectHomeViewState(
                    null,
                    List.of(),
                    List.of(),
                    PendingChangeSummary.empty(),
                    false,
                    null,
                    null,
                    "luma.status.singleplayer_only"
            );
        }

        try {
            BuildProject project = this.query.loadProject(projectName);
            var loadedVariants = new ArrayList<>(this.query.loadVariants(projectName));
            var loadedVersions = new ArrayList<>(this.query.loadVersions(projectName, loadedVariants));
            loadedVersions.sort(Comparator.comparing(io.github.luma.domain.model.ProjectVersion::createdAt).reversed());
            RecoveryDraft draft = this.query.loadDraft(projectName);
            ProjectAdvancedViewState advanced = includeAdvanced
                    ? new ProjectAdvancedViewState(
                            this.query.loadIntegrity(projectName),
                            this.query.loadIntegrations(),
                            this.query.loadJournal(projectName).stream()
                                    .sorted(Comparator.comparing(RecoveryJournalEntry::timestamp).reversed())
                                    .toList()
                    )
                    : null;
            return new ProjectHomeViewState(
                    project,
                    loadedVersions,
                    loadedVariants,
                    draft == null ? PendingChangeSummary.empty() : ChangeStatsFactory.summarizePending(draft.changes()),
                    draft != null && !draft.isEmpty(),
                    this.query.loadOperationSnapshot(project),
                    advanced,
                    status == null || status.isBlank() ? "luma.status.project_ready" : status
            );
        } catch (Exception exception) {
            return new ProjectHomeViewState(
                    null,
                    List.of(),
                    List.of(),
                    PendingChangeSummary.empty(),
                    false,
                    null,
                    new ProjectAdvancedViewState(new ProjectIntegrityReport(false, List.of(), List.of("load-failed")), List.of(), List.of()),
                    "luma.status.project_failed"
            );
        }
    }

    interface Query {

        boolean hasSingleplayerServer();

        BuildProject loadProject(String projectName) throws Exception;

        List<ProjectVariant> loadVariants(String projectName) throws Exception;

        List<ProjectVersion> loadVersions(String projectName, List<ProjectVariant> variants) throws Exception;

        RecoveryDraft loadDraft(String projectName) throws Exception;

        List<RecoveryJournalEntry> loadJournal(String projectName) throws Exception;

        ProjectIntegrityReport loadIntegrity(String projectName) throws Exception;

        List<IntegrationStatus> loadIntegrations();

        OperationSnapshot loadOperationSnapshot(BuildProject project) throws Exception;
    }

    private static final class ServiceQuery implements Query {

        private final Minecraft client = Minecraft.getInstance();
        private final ProjectService projectService = new ProjectService();
        private final RecoveryService recoveryService = new RecoveryService();
        private final ProjectIntegrityService integrityService = new ProjectIntegrityService();
        private final IntegrationStatusService integrationStatusService = new IntegrationStatusService();
        private final OperationSnapshotViewService operationSnapshotViewService = new OperationSnapshotViewService();

        @Override
        public boolean hasSingleplayerServer() {
            return this.client.hasSingleplayerServer();
        }

        @Override
        public BuildProject loadProject(String projectName) throws Exception {
            return this.projectService.loadProject(this.server(), projectName);
        }

        @Override
        public List<ProjectVariant> loadVariants(String projectName) throws Exception {
            return this.projectService.loadVariants(this.server(), projectName);
        }

        @Override
        public List<ProjectVersion> loadVersions(String projectName, List<ProjectVariant> variants) throws Exception {
            return this.projectService.loadVersions(this.server(), projectName);
        }

        @Override
        public RecoveryDraft loadDraft(String projectName) throws Exception {
            return this.recoveryService.loadDraft(this.server(), projectName).orElse(null);
        }

        @Override
        public List<RecoveryJournalEntry> loadJournal(String projectName) throws Exception {
            return this.recoveryService.loadJournal(this.server(), projectName);
        }

        @Override
        public ProjectIntegrityReport loadIntegrity(String projectName) throws Exception {
            return this.integrityService.inspect(this.server(), projectName);
        }

        @Override
        public List<IntegrationStatus> loadIntegrations() {
            return this.integrationStatusService.statuses();
        }

        @Override
        public OperationSnapshot loadOperationSnapshot(BuildProject project) throws Exception {
            return this.operationSnapshotViewService.loadVisibleSnapshot(this.server(), project.id().toString());
        }

        private MinecraftServer server() {
            return ClientProjectAccess.requireSingleplayerServer(this.client);
        }
    }
}
