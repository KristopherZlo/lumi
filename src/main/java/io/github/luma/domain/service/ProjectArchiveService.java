package io.github.luma.domain.service;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.HistoryPackageFileSummary;
import io.github.luma.domain.model.ProjectArchiveExportResult;
import io.github.luma.domain.model.ProjectArchiveImportResult;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectArchiveRepository;
import io.github.luma.storage.repository.ProjectRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

public final class ProjectArchiveService {

    public static final String PACKAGE_FOLDER_NAME = "lumi-projects";
    private static final DateTimeFormatter ARCHIVE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final ProjectArchiveRepository projectArchiveRepository = new ProjectArchiveRepository();
    private final Supplier<Path> gameRootSupplier;

    public ProjectArchiveService() {
        this(() -> FabricLoader.getInstance().getGameDir());
    }

    ProjectArchiveService(Supplier<Path> gameRootSupplier) {
        this.gameRootSupplier = gameRootSupplier;
    }

    public ProjectArchiveExportResult exportProject(MinecraftServer server, String projectName, boolean includePreviews) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        Path archiveFile = this.exportsRoot(server).resolve(this.archiveFileName(project.name(), Instant.now()));
        return new ProjectArchiveExportResult(
                archiveFile,
                this.projectArchiveRepository.exportArchive(layout, project, archiveFile, includePreviews)
        );
    }

    public ProjectArchiveImportResult importProject(MinecraftServer server, String archivePath) throws IOException {
        Path archiveFile = this.resolveArchivePath(server, archivePath);
        ProjectLayout importedLayout = this.projectArchiveRepository.importArchive(this.projectService.projectsRoot(server), archiveFile);
        BuildProject project = this.projectRepository.load(importedLayout)
                .orElseThrow(() -> new IOException("Imported project metadata is missing"));
        HistoryCaptureManager.getInstance().invalidateProjectCache(server);
        return new ProjectArchiveImportResult(archiveFile, this.projectArchiveRepository.loadManifest(archiveFile));
    }

    public Path resolveArchivePath(MinecraftServer server, String archivePath) {
        Path path = Path.of(archivePath);
        if (path.isAbsolute()) {
            return path;
        }
        return this.exportsRoot(server).resolve(path).normalize();
    }

    public Path exportsRoot(MinecraftServer server) {
        return this.packageRoot(server);
    }

    public Path packageRoot(MinecraftServer server) {
        return this.gameRootSupplier.get().resolve(PACKAGE_FOLDER_NAME).normalize();
    }

    public List<HistoryPackageFileSummary> listPackageFiles(MinecraftServer server) throws IOException {
        Path packageRoot = this.packageRoot(server);
        Files.createDirectories(packageRoot);
        try (var stream = Files.list(packageRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .map(this::summarizePackageFile)
                    .sorted(Comparator.comparing(HistoryPackageFileSummary::updatedAt).reversed())
                    .toList();
        }
    }

    public Path ensurePackageRoot(MinecraftServer server) throws IOException {
        Path packageRoot = this.packageRoot(server);
        Files.createDirectories(packageRoot);
        return packageRoot;
    }

    private HistoryPackageFileSummary summarizePackageFile(Path archiveFile) {
        try {
            return new HistoryPackageFileSummary(
                    archiveFile.toAbsolutePath().normalize(),
                    archiveFile.getFileName().toString(),
                    Files.size(archiveFile),
                    Files.getLastModifiedTime(archiveFile).toInstant()
            );
        } catch (IOException exception) {
            return new HistoryPackageFileSummary(
                    archiveFile.toAbsolutePath().normalize(),
                    archiveFile.getFileName().toString(),
                    0L,
                    Instant.EPOCH
            );
        }
    }

    private String archiveFileName(String projectName, Instant now) {
        String safeName = projectName.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        if (safeName.isBlank()) {
            safeName = "project";
        }
        return safeName + "-history-" + ARCHIVE_TIME.format(now) + ".zip";
    }
}
