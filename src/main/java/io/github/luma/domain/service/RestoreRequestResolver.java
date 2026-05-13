package io.github.luma.domain.service;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/**
 * Resolves the requested restore target before the restore plan is built.
 */
final class RestoreRequestResolver {

    private final ProjectService projectService = new ProjectService();
    private final HistoryPackageSafetyScanner safetyScanner = new HistoryPackageSafetyScanner();

    ProjectVersion resolveVersion(
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            String versionId
    ) {
        if (versionId != null && !versionId.isBlank()) {
            return versions.stream()
                    .filter(candidate -> candidate.id().equals(versionId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));
        }

        ProjectVariant activeVariant = this.activeVariant(project, variants);
        return versions.stream()
                .filter(candidate -> candidate.id().equals(activeVariant.headVersionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Variant head version is missing: " + activeVariant.headVersionId()));
    }

    ProjectVariant restoreTargetVariant(List<ProjectVariant> variants, ProjectVersion version, String targetVariantId) {
        if (targetVariantId != null && !targetVariantId.isBlank()) {
            return variants.stream()
                    .filter(candidate -> candidate.id().equals(targetVariantId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + targetVariantId));
        }
        return variants.stream()
                .filter(candidate -> candidate.id().equals(version.variantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Version branch is missing: " + version.variantId()));
    }

    ProjectVariant activeVariant(BuildProject project, List<ProjectVariant> variants) {
        return variants.stream()
                .filter(variant -> variant.id().equals(project.activeVariantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + project.name()));
    }

    void requireTrustedImportedRestore(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            boolean trustedImportedPackage
    ) throws IOException {
        if (!this.isImportedReviewProject(level, project)) {
            return;
        }
        var report = this.safetyScanner.scanProjectHistory(layout, versions);
        if (report.requiresTrustedConfirmation() && !trustedImportedPackage) {
            throw new IllegalArgumentException("Imported package contains executable world-state data. Confirm that you trust this package before restoring it.");
        }
    }

    private boolean isImportedReviewProject(ServerLevel level, BuildProject project) throws IOException {
        if (project == null || project.name() == null || !project.name().contains(" - Shared ")) {
            return false;
        }
        return this.projectService.listProjects(level.getServer()).stream()
                .anyMatch(candidate -> project.id().equals(candidate.id()) && !project.name().equals(candidate.name()));
    }
}
