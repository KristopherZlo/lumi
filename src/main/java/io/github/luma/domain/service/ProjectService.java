package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldOriginInfo;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.SnapshotWriter;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import io.github.luma.storage.repository.WorldOriginRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Owns project creation, lookup, and project-level metadata updates.
 *
 * <p>This service exposes the main entry points for discovering projects in the
 * current world, creating bounded or whole-dimension workspaces, and resolving
 * stable ids used by history storage.
 */
public final class ProjectService {

    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final SnapshotWriter snapshotWriter = new SnapshotWriter();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final PreviewCaptureRequestService previewCaptureRequestService = new PreviewCaptureRequestService();
    private final WorldOriginRepository worldOriginRepository = new WorldOriginRepository();

    public List<BuildProject> listProjects(MinecraftServer server) throws IOException {
        return this.projectRepository.loadAll(this.projectsRoot(server));
    }

    public ProjectLayout resolveLayout(MinecraftServer server, String projectName) throws IOException {
        return this.projectRepository.findLayoutByProjectName(this.projectsRoot(server), projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectName));
    }

    public BuildProject loadProject(MinecraftServer server, String projectName) throws IOException {
        return this.projectRepository.load(this.resolveLayout(server, projectName))
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
    }

    /**
     * Ensures that the current dimension has one automatic whole-dimension
     * workspace.
     */
    public BuildProject ensureWorldProject(ServerLevel level, String author) throws IOException {
        Path root = this.projectsRoot(level.getServer());
        Files.createDirectories(root);
        this.ensureWorldOrigin(level.getServer());

        for (BuildProject project : this.projectRepository.loadAll(root)) {
            if (project.tracksWholeDimension() && project.dimensionId().equals(level.dimension().identifier().toString())) {
                ProjectLayout existingLayout = this.resolveLayout(level.getServer(), project.name());
                this.ensureWorldRootVersion(existingLayout, project, author, Instant.now());
                return project;
            }
        }

        Instant now = Instant.now();
        String projectName = this.uniqueProjectName(root, this.defaultProjectName(level));
        ProjectLayout layout = ProjectLayout.of(root, projectName);
        BuildProject project = BuildProject.createWorldWorkspace(projectName, level.dimension().identifier().toString(), now);
        String rootVersionId = this.ensureWorldRootVersion(layout, project, author, now);

        this.projectRepository.save(layout, project);
        this.variantRepository.save(layout, List.of(ProjectVariant.main(rootVersionId, now)));
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "workspace-created",
                "World workspace tracking enabled with initial root",
                rootVersionId,
                "main"
        ));
        HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
        return project;
    }

    public WorldOriginInfo ensureWorldOrigin(MinecraftServer server) throws IOException {
        return this.worldOriginRepository.ensure(server);
    }

    public void bootstrapWorld(MinecraftServer server) throws IOException {
        this.ensureWorldOrigin(server);
        for (BuildProject project : this.listProjects(server)) {
            if (!project.tracksWholeDimension()) {
                continue;
            }
            this.ensureWorldRootVersion(this.resolveLayout(server, project.name()), project, "Lumi", Instant.now());
        }
    }

    /**
     * Creates a bounded project with an initial checkpoint snapshot and main
     * variant head.
     */
    public BuildProject createProject(ServerLevel level, String name, BlockPos from, BlockPos to, String author) throws IOException {
        Path root = this.projectsRoot(level.getServer());
        Files.createDirectories(root);

        ProjectLayout layout = ProjectLayout.of(root, name);
        if (Files.exists(layout.root())) {
            throw new IllegalArgumentException("Project already exists: " + name);
        }

        Instant now = Instant.now();
        Bounds3i bounds = Bounds3i.of(from, to);
        BuildProject project = BuildProject.create(name, level.dimension().identifier().toString(), bounds, BlockPoint.from(from), now);

        String versionId = versionId(1);
        String snapshotId = snapshotId(1);
        this.snapshotWriter.capture(
                layout,
                project.id().toString(),
                snapshotId,
                ChunkSelectionFactory.fromBounds(bounds),
                level,
                now
        );
        this.projectRepository.save(layout, project);
        this.variantRepository.save(layout, List.of(ProjectVariant.main(versionId, now)));
        this.versionRepository.save(layout, new ProjectVersion(
                versionId,
                project.id().toString(),
                "main",
                "",
                snapshotId,
                List.of(),
                VersionKind.INITIAL,
                author,
                "Initial version",
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                now
        ));
        if (project.settings().previewGenerationEnabled()) {
            this.previewCaptureRequestService.queue(layout, versionId, project.dimensionId(), bounds);
        }
        this.recoveryRepository.appendJournalEntry(layout, new io.github.luma.domain.model.RecoveryJournalEntry(
                now,
                "project-created",
                "Project created with initial checkpoint snapshot",
                versionId,
                "main"
        ));
        HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());

        return project;
    }

    public BuildProject updateSettings(MinecraftServer server, String projectName, ProjectSettings settings) throws IOException {
        ProjectLayout layout = this.resolveLayout(server, projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        BuildProject updated = project
                .withSettings(settings, Instant.now())
                .withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION);
        this.projectRepository.save(layout, updated);
        return updated;
    }

    public BuildProject setFavorite(MinecraftServer server, String projectName, boolean favorite) throws IOException {
        ProjectLayout layout = this.resolveLayout(server, projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        BuildProject updated = project
                .withFavorite(favorite, Instant.now())
                .withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION);
        this.projectRepository.save(layout, updated);
        return updated;
    }

    public BuildProject setArchived(MinecraftServer server, String projectName, boolean archived) throws IOException {
        ProjectLayout layout = this.resolveLayout(server, projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        BuildProject updated = project
                .withArchived(archived, Instant.now())
                .withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION);
        this.projectRepository.save(layout, updated);
        return updated;
    }

    public List<ProjectVersion> loadVersions(MinecraftServer server, String projectName) throws IOException {
        return this.versionRepository.loadAll(this.resolveLayout(server, projectName));
    }

    public List<ProjectVariant> loadVariants(MinecraftServer server, String projectName) throws IOException {
        return this.variantRepository.loadAll(this.resolveLayout(server, projectName));
    }

    public Path projectsRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("lumi").resolve("projects");
    }

    public String defaultProjectName(ServerLevel level) {
        String worldName = level.getServer().getWorldData().getLevelName();
        String dimension = this.dimensionLabel(level.dimension().identifier().toString());
        if ("Overworld".equals(dimension)) {
            return worldName;
        }
        return worldName + " - " + dimension;
    }

    public static String versionId(int number) {
        return String.format(Locale.ROOT, "v%04d", number);
    }

    public static String snapshotId(int number) {
        return String.format(Locale.ROOT, "snapshot-%04d", number);
    }

    public static String patchId(int number) {
        return String.format(Locale.ROOT, "patch-%04d", number);
    }

    private String uniqueProjectName(Path root, String preferredName) throws IOException {
        List<String> existingNames = new ArrayList<>();
        for (BuildProject project : this.projectRepository.loadAll(root)) {
            existingNames.add(project.name().toLowerCase(Locale.ROOT));
        }

        if (!existingNames.contains(preferredName.toLowerCase(Locale.ROOT))) {
            return preferredName;
        }

        int suffix = 2;
        while (existingNames.contains((preferredName + " " + suffix).toLowerCase(Locale.ROOT))) {
            suffix += 1;
        }
        return preferredName + " " + suffix;
    }

    private String dimensionLabel(String dimensionId) {
        if ("minecraft:the_nether".equals(dimensionId)) {
            return "Nether";
        }
        if ("minecraft:the_end".equals(dimensionId)) {
            return "End";
        }
        return "Overworld";
    }

    private String ensureWorldRootVersion(
            ProjectLayout layout,
            BuildProject project,
            String author,
            Instant now
    ) throws IOException {
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        if (!versions.isEmpty()) {
            return versions.getFirst().id();
        }

        this.projectRepository.save(layout, project);
        String versionId = versionId(1);
        ProjectVersion rootVersion = new ProjectVersion(
                versionId,
                project.id().toString(),
                "main",
                "",
                "",
                List.of(),
                VersionKind.WORLD_ROOT,
                author == null ? "Lumi" : author,
                "Initial",
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                now
        );
        this.versionRepository.save(layout, rootVersion);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        if (variants.isEmpty()) {
            this.variantRepository.save(layout, List.of(ProjectVariant.main(versionId, now)));
        } else {
            List<ProjectVariant> updated = new ArrayList<>();
            for (ProjectVariant variant : variants) {
                if (variant.headVersionId() == null || variant.headVersionId().isBlank()) {
                    updated.add(new ProjectVariant(
                            variant.id(),
                            variant.name(),
                            variant.baseVersionId() == null || variant.baseVersionId().isBlank() ? versionId : variant.baseVersionId(),
                            versionId,
                            variant.main(),
                            variant.createdAt()
                    ));
                } else {
                    updated.add(variant);
                }
            }
            this.variantRepository.save(layout, updated);
        }
        return versionId;
    }
}
