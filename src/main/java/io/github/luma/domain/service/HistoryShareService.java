package io.github.luma.domain.service;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.HistoryPackageImportResult;
import io.github.luma.domain.model.ImportedHistoryProjectSummary;
import io.github.luma.domain.model.ProjectArchiveExportResult;
import io.github.luma.domain.model.ProjectArchiveManifest;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectArchiveRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;

public final class HistoryShareService {

    private static final DateTimeFormatter ARCHIVE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final ProjectService projectService = new ProjectService();
    private final ProjectArchiveService projectArchiveService = new ProjectArchiveService();
    private final ProjectArchiveRepository projectArchiveRepository = new ProjectArchiveRepository();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();

    public ProjectArchiveExportResult exportVariantPackage(
            MinecraftServer server,
            String projectName,
            String variantId,
            boolean includePreviews
    ) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        ProjectVariant variant = this.variantRepository.loadAll(layout).stream()
                .filter(candidate -> candidate.id().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + variantId));
        Path archiveFile = this.projectArchiveService.exportsRoot(server)
                .resolve(this.archiveFileName(project.name(), variant.name(), Instant.now()));
        ProjectArchiveManifest manifest = this.projectArchiveRepository.exportVariantArchive(
                layout,
                project,
                variant,
                this.versionRepository.loadAll(layout),
                archiveFile,
                includePreviews
        );
        return new ProjectArchiveExportResult(archiveFile, manifest);
    }

    public HistoryPackageImportResult importVariantPackage(
            MinecraftServer server,
            String targetProjectName,
            String archivePath
    ) throws IOException {
        ProjectLayout targetLayout = this.projectService.resolveLayout(server, targetProjectName);
        BuildProject targetProject = this.projectRepository.load(targetLayout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + targetProjectName));
        Path archiveFile = this.projectArchiveService.resolveArchivePath(server, archivePath);
        HistoryPackageImportResult result = this.importVariantPackage(this.projectService.projectsRoot(server), targetProject, archiveFile);
        HistoryCaptureManager.getInstance().invalidateProjectCache(server);
        return result;
    }

    HistoryPackageImportResult importVariantPackage(Path projectsRoot, BuildProject targetProject, Path archiveFile) throws IOException {
        ProjectArchiveManifest manifest = this.projectArchiveRepository.loadManifest(archiveFile);
        if (!manifest.scopeOrDefault().variantScope()) {
            throw new IOException("Archive does not contain a variant history package");
        }
        if (!targetProject.id().toString().equals(manifest.projectId())) {
            throw new IOException("History package belongs to a different project");
        }

        Path tempProjectsRoot = Files.createTempDirectory(projectsRoot, "share-import-");
        try {
            ProjectLayout importedLayout = this.projectArchiveRepository.importArchive(tempProjectsRoot, archiveFile);
            BuildProject importedProject = this.projectRepository.load(importedLayout)
                    .orElseThrow(() -> new IOException("Imported project metadata is missing"));
            ProjectVariant importedVariant = this.variantRepository.loadAll(importedLayout).stream()
                    .filter(candidate -> candidate.id().equals(manifest.scopeOrDefault().variantId()))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Imported variant is missing from package"));

            String importedProjectName = this.uniqueImportedProjectName(projectsRoot, targetProject.name(), importedVariant.name());
            BuildProject rewrittenProject = new BuildProject(
                    importedProject.schemaVersion(),
                    importedProject.id(),
                    importedProjectName,
                    importedProject.description(),
                    importedProject.minecraftVersion(),
                    importedProject.modLoader(),
                    importedProject.dimensionId(),
                    importedProject.bounds(),
                    importedProject.origin(),
                    importedVariant.id(),
                    importedVariant.id(),
                    importedProject.createdAt(),
                    Instant.now(),
                    importedProject.settings(),
                    false,
                    false
            );
            ProjectVariant rewrittenVariant = new ProjectVariant(
                    importedVariant.id(),
                    importedVariant.name(),
                    importedVariant.baseVersionId(),
                    importedVariant.headVersionId(),
                    true,
                    importedVariant.createdAt()
            );
            this.projectRepository.save(importedLayout, rewrittenProject);
            this.variantRepository.save(importedLayout, List.of(rewrittenVariant));

            Path finalRoot = ProjectLayout.of(projectsRoot, importedProjectName).root();
            Files.move(importedLayout.root(), finalRoot);
            return new HistoryPackageImportResult(
                    archiveFile,
                    importedProjectName,
                    rewrittenVariant.id(),
                    rewrittenVariant.name(),
                    manifest
            );
        } finally {
            this.deleteTree(tempProjectsRoot);
        }
    }

    public List<ImportedHistoryProjectSummary> listImportedProjects(MinecraftServer server, String targetProjectName) throws IOException {
        BuildProject targetProject = this.projectService.loadProject(server, targetProjectName);
        List<ImportedHistoryProjectSummary> importedProjects = new java.util.ArrayList<>();
        for (BuildProject candidate : this.projectService.listProjects(server)) {
            if (!candidate.id().equals(targetProject.id()) || candidate.name().equals(targetProject.name())) {
                continue;
            }
            ProjectLayout layout = this.projectService.resolveLayout(server, candidate.name());
            ProjectVariant variant = this.variantRepository.loadAll(layout).stream()
                    .filter(item -> item.id().equals(candidate.activeVariantId()))
                    .findFirst()
                    .orElse(null);
            if (variant == null) {
                continue;
            }
            importedProjects.add(new ImportedHistoryProjectSummary(
                    candidate.name(),
                    variant.id(),
                    variant.name(),
                    variant.headVersionId(),
                    candidate.updatedAt()
            ));
        }
        importedProjects.sort(Comparator.comparing(ImportedHistoryProjectSummary::updatedAt).reversed());
        return List.copyOf(importedProjects);
    }

    public void deleteImportedProject(
            MinecraftServer server,
            String targetProjectName,
            String importedProjectName
    ) throws IOException {
        BuildProject targetProject = this.projectService.loadProject(server, targetProjectName);
        this.deleteImportedProject(this.projectService.projectsRoot(server), targetProject, importedProjectName);
        HistoryCaptureManager.getInstance().invalidateProjectCache(server);
    }

    void deleteImportedProject(Path projectsRoot, BuildProject targetProject, String importedProjectName) throws IOException {
        if (importedProjectName == null || importedProjectName.isBlank()) {
            throw new IllegalArgumentException("Imported package name is missing");
        }
        if (targetProject.name().equals(importedProjectName)) {
            throw new IllegalArgumentException("Cannot delete the active project from Share");
        }

        ProjectLayout importedLayout = this.projectRepository.findLayoutByProjectName(projectsRoot, importedProjectName)
                .orElseThrow(() -> new IllegalArgumentException("Imported package not found: " + importedProjectName));
        BuildProject importedProject = this.projectRepository.load(importedLayout)
                .orElseThrow(() -> new IllegalArgumentException("Imported package metadata is missing: " + importedProjectName));
        if (!targetProject.id().equals(importedProject.id())) {
            throw new IllegalArgumentException("Imported package belongs to a different project lineage");
        }

        Path normalizedRoot = projectsRoot.toAbsolutePath().normalize();
        Path importedRoot = importedLayout.root().toAbsolutePath().normalize();
        if (!importedRoot.startsWith(normalizedRoot)) {
            throw new IOException("Imported package storage is outside the projects root");
        }

        this.deleteTree(importedLayout.root());
    }

    private String archiveFileName(String projectName, String variantName, Instant now) {
        return this.safeName(projectName) + "-" + this.safeName(variantName) + "-history-" + ARCHIVE_TIME.format(now) + ".zip";
    }

    private String safeName(String value) {
        String safe = value == null ? "" : value.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        return safe.isBlank() ? "history" : safe;
    }

    private String uniqueImportedProjectName(Path projectsRoot, String projectName, String variantName) throws IOException {
        String preferred = projectName + " - Shared " + (variantName == null || variantName.isBlank() ? "variant" : variantName);
        List<String> existingNames = this.projectRepository.loadAll(projectsRoot).stream()
                .map(BuildProject::name)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .sorted(Comparator.naturalOrder())
                .toList();
        if (!existingNames.contains(preferred.toLowerCase(Locale.ROOT))) {
            return preferred;
        }
        int suffix = 2;
        while (existingNames.contains((preferred + " " + suffix).toLowerCase(Locale.ROOT))) {
            suffix += 1;
        }
        return preferred + " " + suffix;
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
