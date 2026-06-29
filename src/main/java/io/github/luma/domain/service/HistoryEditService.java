package io.github.luma.domain.service;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.HistoryTombstones;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVersionTags;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.HistoryTombstoneRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;

/**
 * Owns safe metadata-only history edits.
 */
public final class HistoryEditService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectLayoutResolver layoutResolver;
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final HistoryTombstoneRepository tombstoneRepository = new HistoryTombstoneRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final ProjectCacheInvalidator projectCacheInvalidator;

    public HistoryEditService() {
        this.layoutResolver = this.projectService::resolveLayout;
        this.projectCacheInvalidator = (server, projectId) -> HistoryCaptureManager.getInstance().invalidateProjectCache(server);
    }

    HistoryEditService(ProjectLayoutResolver layoutResolver, ProjectCacheInvalidator projectCacheInvalidator) {
        this.layoutResolver = layoutResolver;
        this.projectCacheInvalidator = projectCacheInvalidator;
    }

    public ProjectVersion renameVersion(
            MinecraftServer server,
            String projectName,
            String versionId,
            String message
    ) throws IOException {
        if (message == null || message.trim().isBlank()) {
            throw new IllegalArgumentException("Save name is required");
        }
        ProjectLayout layout = this.layoutResolver.resolveLayout(server, projectName);
        BuildProject project = this.loadProject(layout, projectName);
        HistoryTombstones tombstones = this.tombstoneRepository.load(layout);
        ProjectVersion version = this.versionRepository.load(layout, versionId)
                .filter(candidate -> !tombstones.versionDeleted(candidate.id()))
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));

        ProjectVersion renamed = new ProjectVersion(
                version.id(),
                version.projectId(),
                version.variantId(),
                version.parentVersionId(),
                version.snapshotId(),
                version.patchIds(),
                version.versionKind(),
                version.author(),
                message.trim(),
                version.stats(),
                version.preview(),
                version.sourceInfo(),
                version.createdAt()
        );
        Instant now = Instant.now();
        this.versionRepository.save(layout, renamed);
        this.projectRepository.save(layout, project.withUpdatedAt(now).withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION));
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "version-renamed",
                "Renamed save " + version.id(),
                version.id(),
                version.variantId()
        ));
        this.projectCacheInvalidator.invalidate(server, project.id().toString());
        return renamed;
    }

    public ProjectVersion updateVersionTags(
            MinecraftServer server,
            String projectName,
            String versionId,
            List<String> tags
    ) throws IOException {
        ProjectLayout layout = this.layoutResolver.resolveLayout(server, projectName);
        BuildProject project = this.loadProject(layout, projectName);
        HistoryTombstones tombstones = this.tombstoneRepository.load(layout);
        ProjectVersion version = this.versionRepository.load(layout, versionId)
                .filter(candidate -> !tombstones.versionDeleted(candidate.id()))
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));

        ProjectVersion tagged = ProjectVersionTags.withTags(version, tags);
        Instant now = Instant.now();
        this.versionRepository.save(layout, tagged);
        this.projectRepository.save(layout, project.withUpdatedAt(now).withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION));
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "version-tags-updated",
                "Updated tags for save " + version.id(),
                version.id(),
                version.variantId()
        ));
        this.projectCacheInvalidator.invalidate(server, project.id().toString());
        return tagged;
    }

    public void deleteVersion(MinecraftServer server, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.layoutResolver.resolveLayout(server, projectName);
        BuildProject project = this.loadProject(layout, projectName);
        HistoryTombstones tombstones = this.tombstoneRepository.load(layout);
        ProjectVersion version = this.versionRepository.load(layout, versionId)
                .filter(candidate -> !tombstones.versionDeleted(candidate.id()))
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));
        if (version.versionKind() == VersionKind.INITIAL
                || version.versionKind() == VersionKind.WORLD_ROOT
                || version.parentVersionId() == null
                || version.parentVersionId().isBlank()) {
            throw new IllegalArgumentException("Root saves cannot be deleted");
        }

        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        List<ProjectVariant> visibleHeadVariants = variants.stream()
                .filter(variant -> !tombstones.variantDeleted(variant.id()))
                .filter(variant -> version.id().equals(variant.headVersionId()))
                .toList();

        Instant now = Instant.now();
        boolean variantsChanged = false;
        for (ProjectVariant headVariant : visibleHeadVariants) {
            variants = replaceVariant(variants, new ProjectVariant(
                    headVariant.id(),
                    headVariant.name(),
                    headVariant.baseVersionId(),
                    version.parentVersionId(),
                    headVariant.main(),
                    headVariant.createdAt(),
                    headVariant.switchKey()
            ));
            variantsChanged = true;
        }
        for (ProjectVariant variant : variants) {
            if (!tombstones.variantDeleted(variant.id()) && version.id().equals(variant.baseVersionId())) {
                variants = replaceVariant(variants, new ProjectVariant(
                        variant.id(),
                        variant.name(),
                        version.parentVersionId(),
                        variant.headVersionId(),
                        variant.main(),
                        variant.createdAt(),
                        variant.switchKey()
                ));
                variantsChanged = true;
            }
        }
        if (variantsChanged) {
            this.variantRepository.save(layout, variants);
        }
        this.tombstoneRepository.tombstoneVersion(layout, version.id(), now);
        this.projectRepository.save(layout, project.withUpdatedAt(now).withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION));
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "version-deleted",
                "Soft-deleted save " + version.id(),
                version.id(),
                version.variantId()
        ));
        this.projectCacheInvalidator.invalidate(server, project.id().toString());
    }

    public void restoreDeletedVersion(MinecraftServer server, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.layoutResolver.resolveLayout(server, projectName);
        BuildProject project = this.loadProject(layout, projectName);
        HistoryTombstones tombstones = this.tombstoneRepository.load(layout);
        ProjectVersion version = this.versionRepository.load(layout, versionId)
                .filter(candidate -> tombstones.versionDeleted(candidate.id()))
                .orElseThrow(() -> new IllegalArgumentException("Deleted version not found: " + versionId));

        Instant now = Instant.now();
        this.tombstoneRepository.restoreVersion(layout, version.id(), now);
        this.projectRepository.save(layout, project.withUpdatedAt(now).withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION));
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "version-restored",
                "Restored soft-deleted save " + version.id(),
                version.id(),
                version.variantId()
        ));
        this.projectCacheInvalidator.invalidate(server, project.id().toString());
    }

    public void deleteVariant(MinecraftServer server, String projectName, String variantId) throws IOException {
        ProjectLayout layout = this.layoutResolver.resolveLayout(server, projectName);
        BuildProject project = this.loadProject(layout, projectName);
        HistoryTombstones tombstones = this.tombstoneRepository.load(layout);
        ProjectVariant variant = this.variantRepository.loadAll(layout).stream()
                .filter(candidate -> candidate.id().equals(variantId))
                .filter(candidate -> !tombstones.variantDeleted(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + variantId));
        if (variant.main() || variant.id().equals(project.mainVariantId()) || "main".equals(variant.id())) {
            throw new IllegalArgumentException("Main branch cannot be deleted");
        }
        if (variant.id().equals(project.activeVariantId())) {
            throw new IllegalArgumentException("Active branch cannot be deleted");
        }

        Instant now = Instant.now();
        this.variantRepository.save(layout, replaceVariant(
                this.variantRepository.loadAll(layout),
                variant.withSwitchKey("")
        ));
        this.tombstoneRepository.tombstoneVariant(layout, variant.id(), now);
        this.projectRepository.save(layout, project.withUpdatedAt(now).withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION));
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "variant-deleted",
                "Soft-deleted variant " + variant.id(),
                variant.headVersionId(),
                variant.id()
        ));
        this.projectCacheInvalidator.invalidate(server, project.id().toString());
    }

    private BuildProject loadProject(ProjectLayout layout, String projectName) throws IOException {
        return this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
    }

    private static List<ProjectVariant> replaceVariant(List<ProjectVariant> variants, ProjectVariant replacement) {
        List<ProjectVariant> next = new ArrayList<>();
        for (ProjectVariant variant : variants) {
            next.add(variant.id().equals(replacement.id()) ? replacement : variant);
        }
        return List.copyOf(next);
    }

    interface ProjectLayoutResolver {

        ProjectLayout resolveLayout(MinecraftServer server, String projectName) throws IOException;
    }

    interface ProjectCacheInvalidator {

        void invalidate(MinecraftServer server, String projectId);
    }
}
