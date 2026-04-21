package io.github.luma.domain.service;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectArchiveExportResult;
import io.github.luma.domain.model.ProjectArchiveImportResult;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectArchiveRepository;
import io.github.luma.storage.repository.ProjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class ProjectArchiveService {

    private static final DateTimeFormatter ARCHIVE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final ProjectArchiveRepository projectArchiveRepository = new ProjectArchiveRepository();

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
        return server.getWorldPath(LevelResource.ROOT).resolve("lumi").resolve("exports");
    }

    private String archiveFileName(String projectName, Instant now) {
        String safeName = projectName.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        if (safeName.isBlank()) {
            safeName = "project";
        }
        return safeName + "-history-" + ARCHIVE_TIME.format(now) + ".zip";
    }
}
