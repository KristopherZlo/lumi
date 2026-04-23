package io.github.luma.ui.controller;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.HistoryPackageImportResult;
import io.github.luma.domain.model.ImportedHistoryProjectSummary;
import io.github.luma.domain.model.ProjectArchiveExportResult;
import io.github.luma.domain.model.VariantMergePlan;
import io.github.luma.domain.service.HistoryShareService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.VariantMergeService;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class ShareFlowController {

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectService projectService = new ProjectService();
    private final HistoryShareService historyShareService = new HistoryShareService();
    private final VariantMergeService variantMergeService = new VariantMergeService();

    public ProjectArchiveExportResult exportVariantPackage(String projectName, String variantId) {
        try {
            return this.historyShareService.exportVariantPackage(
                    ClientProjectAccess.requireSingleplayerServer(this.client),
                    projectName,
                    variantId,
                    false
            );
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Variant package export failed for project {} variant {}", projectName, variantId, exception);
            return null;
        }
    }

    public HistoryPackageImportResult importVariantPackage(String projectName, String archivePath) {
        try {
            return this.historyShareService.importVariantPackage(
                    ClientProjectAccess.requireSingleplayerServer(this.client),
                    projectName,
                    archivePath
            );
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Variant package import failed for project {}", projectName, exception);
            return null;
        }
    }

    public List<ImportedHistoryProjectSummary> listImportedProjects(String projectName) {
        try {
            return this.historyShareService.listImportedProjects(
                    ClientProjectAccess.requireSingleplayerServer(this.client),
                    projectName
            );
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Imported project listing failed for project {}", projectName, exception);
            return List.of();
        }
    }

    public VariantMergePlan previewMerge(
            String targetProjectName,
            String sourceProjectName,
            String sourceVariantId,
            String targetVariantId
    ) {
        try {
            return this.variantMergeService.previewMerge(
                    ClientProjectAccess.requireSingleplayerServer(this.client),
                    targetProjectName,
                    sourceProjectName,
                    sourceVariantId,
                    targetVariantId
            );
        } catch (Exception exception) {
            LumaMod.LOGGER.warn(
                    "Merge preview failed for target project {} from {}:{}",
                    targetProjectName,
                    sourceProjectName,
                    sourceVariantId,
                    exception
            );
            return null;
        }
    }

    public String startMerge(
            String targetProjectName,
            String sourceProjectName,
            String sourceVariantId,
            String targetVariantId
    ) {
        try {
            this.variantMergeService.startMerge(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, targetProjectName),
                    targetProjectName,
                    sourceProjectName,
                    sourceVariantId,
                    targetVariantId,
                    this.client.getUser().getName()
            );
            return "luma.status.merge_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Merge start rejected for project {}", targetProjectName, exception);
            return "luma.status.world_operation_busy";
        } catch (IllegalArgumentException exception) {
            LumaMod.LOGGER.warn("Merge start blocked for project {}", targetProjectName, exception);
            return exception.getMessage() != null && exception.getMessage().contains("does not add any new changes")
                    ? "luma.status.merge_no_changes"
                    : "luma.status.merge_conflicts_found";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Merge start failed for project {}", targetProjectName, exception);
            return "luma.status.operation_failed";
        }
    }
}
