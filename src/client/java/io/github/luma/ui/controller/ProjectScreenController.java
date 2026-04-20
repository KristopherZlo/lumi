package io.github.luma.ui.controller;

import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.RestoreService;
import io.github.luma.domain.service.VariantService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.domain.service.DiffService;
import io.github.luma.domain.service.MaterialDeltaService;
import io.github.luma.domain.service.ProjectIntegrityService;
import io.github.luma.integration.common.IntegrationStatusService;
import io.github.luma.ui.state.ProjectTab;
import io.github.luma.ui.state.ProjectViewState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class ProjectScreenController {

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectService projectService = new ProjectService();
    private final VersionService versionService = new VersionService();
    private final RestoreService restoreService = new RestoreService();
    private final VariantService variantService = new VariantService();
    private final RecoveryService recoveryService = new RecoveryService();
    private final DiffService diffService = new DiffService();
    private final MaterialDeltaService materialDeltaService = new MaterialDeltaService();
    private final ProjectIntegrityService integrityService = new ProjectIntegrityService();
    private final IntegrationStatusService integrationStatusService = new IntegrationStatusService();

    public ProjectViewState loadState(String projectName, ProjectTab selectedTab, String selectedVersionId, String status) {
        if (!this.client.hasSingleplayerServer()) {
            return new ProjectViewState(
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    new io.github.luma.domain.model.ProjectIntegrityReport(true, List.of(), List.of()),
                    selectedTab,
                    "luma.status.singleplayer_only"
            );
        }

        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            var loadedVersions = new ArrayList<>(this.projectService.loadVersions(server, projectName));
            loadedVersions.sort(java.util.Comparator.comparing(io.github.luma.domain.model.ProjectVersion::createdAt).reversed());
            var loadedJournal = new ArrayList<>(this.recoveryService.loadJournal(server, projectName));
            loadedJournal.sort(java.util.Comparator.comparing(io.github.luma.domain.model.RecoveryJournalEntry::timestamp).reversed());
            var project = this.projectService.loadProject(server, projectName);
            var selectedVersion = this.resolveSelectedVersion(loadedVersions, selectedVersionId);
            var diff = selectedVersion != null
                    ? this.diffService.compareVersionToParent(server, projectName, selectedVersion.id())
                    : null;
            return new ProjectViewState(
                    project,
                    loadedVersions,
                    this.projectService.loadVariants(server, projectName),
                    loadedJournal,
                    this.recoveryService.loadDraft(server, projectName).orElse(null),
                    selectedVersion,
                    diff,
                    diff == null ? List.of() : this.materialDeltaService.summarize(diff),
                    this.integrationStatusService.statuses(),
                    this.integrityService.inspect(server, projectName),
                    selectedTab,
                    status == null || status.isBlank() ? "luma.status.project_ready" : status
            );
        } catch (Exception exception) {
            return new ProjectViewState(
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    new io.github.luma.domain.model.ProjectIntegrityReport(false, List.of(), List.of("load-failed")),
                    selectedTab,
                    "luma.status.project_failed"
            );
        }
    }

    public boolean hasRecoveryDraft(String projectName) {
        if (!this.client.hasSingleplayerServer()) {
            return false;
        }

        try {
            return this.recoveryService.hasDraft(ClientProjectAccess.requireSingleplayerServer(this.client), projectName);
        } catch (Exception exception) {
            return false;
        }
    }

    public String saveVersion(String projectName, String message) {
        try {
            this.versionService.saveVersion(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    message,
                    this.client.getUser().getName()
            );
            return "luma.status.version_saved";
        } catch (Exception exception) {
            return "luma.status.operation_failed";
        }
    }

    public String restoreVersion(String projectName, String versionId) {
        try {
            this.restoreService.restore(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    versionId
            );
            return "luma.status.version_restored";
        } catch (Exception exception) {
            return "luma.status.operation_failed";
        }
    }

    public String createVariant(String projectName, String variantName, String fromVersionId) {
        try {
            this.variantService.createVariant(
                    ClientProjectAccess.requireSingleplayerServer(this.client),
                    projectName,
                    variantName,
                    fromVersionId
            );
            return "luma.status.variant_created";
        } catch (Exception exception) {
            return "luma.status.operation_failed";
        }
    }

    public String switchVariant(String projectName, String variantId) {
        try {
            this.variantService.switchVariant(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    variantId
            );
            return "luma.status.variant_switched";
        } catch (Exception exception) {
            return "luma.status.operation_failed";
        }
    }

    public String refreshPreview(String projectName, String versionId) {
        try {
            this.versionService.refreshPreview(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    versionId
            );
            return "luma.status.preview_refreshed";
        } catch (Exception exception) {
            return "luma.status.operation_failed";
        }
    }

    public String resolvePreviewPath(String projectName, String versionId) {
        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            return this.projectService.resolveLayout(server, projectName).previewFile(versionId).toString();
        } catch (Exception exception) {
            return "";
        }
    }

    private io.github.luma.domain.model.ProjectVersion resolveSelectedVersion(
            List<io.github.luma.domain.model.ProjectVersion> versions,
            String selectedVersionId
    ) {
        if (versions.isEmpty()) {
            return null;
        }

        if (selectedVersionId != null && !selectedVersionId.isBlank()) {
            for (var version : versions) {
                if (version.id().equals(selectedVersionId)) {
                    return version;
                }
            }
        }

        return versions.getFirst();
    }
}
