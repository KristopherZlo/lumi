package io.github.luma.ui.controller;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.ui.state.VariantsViewState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

public final class VariantsScreenController {

    private final Query query;

    public VariantsScreenController() {
        this(new ServiceQuery());
    }

    VariantsScreenController(Query query) {
        this.query = query;
    }

    public VariantsViewState loadState(String projectName, String status) {
        if (!this.query.hasSingleplayerServer()) {
            return new VariantsViewState(null, List.of(), List.of(), null, "luma.status.singleplayer_only");
        }

        try {
            BuildProject project = this.query.loadProject(projectName);
            var loadedVariants = new ArrayList<>(this.query.loadVariants(projectName));
            var loadedVersions = new ArrayList<>(this.query.loadVersions(projectName, loadedVariants));
            loadedVersions.sort(Comparator.comparing(io.github.luma.domain.model.ProjectVersion::createdAt).reversed());
            return new VariantsViewState(
                    project,
                    loadedVersions,
                    loadedVariants,
                    this.query.loadOperationSnapshot(project),
                    status == null || status.isBlank() ? "luma.status.project_ready" : status
            );
        } catch (Exception exception) {
            return new VariantsViewState(null, List.of(), List.of(), null, "luma.status.project_failed");
        }
    }

    interface Query {

        boolean hasSingleplayerServer();

        BuildProject loadProject(String projectName) throws Exception;

        List<ProjectVariant> loadVariants(String projectName) throws Exception;

        List<ProjectVersion> loadVersions(String projectName, List<ProjectVariant> variants) throws Exception;

        OperationSnapshot loadOperationSnapshot(BuildProject project) throws Exception;
    }

    private static final class ServiceQuery implements Query {

        private final Minecraft client = Minecraft.getInstance();
        private final ProjectService projectService = new ProjectService();
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
        public OperationSnapshot loadOperationSnapshot(BuildProject project) throws Exception {
            return this.operationSnapshotViewService.loadVisibleSnapshot(this.server(), project.id().toString());
        }

        private MinecraftServer server() {
            return ClientProjectAccess.requireSingleplayerServer(this.client);
        }
    }
}
