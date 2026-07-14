package io.github.luma.domain.service;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVariantSwitchKeys;
import io.github.luma.domain.model.RecoveryJournalEntry;
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
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Manages project variants and variant switching.
 *
 * <p>Variants act as lightweight branch heads within a single project. This
 * service keeps variant metadata consistent, blocks unsafe transitions when a
 * recovery draft exists, and restores the target head when switching variants.
 */
public final class VariantService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectLayoutResolver layoutResolver;
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final HistoryTombstoneRepository tombstoneRepository = new HistoryTombstoneRepository();
    private final RestoreService restoreService = new RestoreService();
    private final ProjectCacheInvalidator projectCacheInvalidator;

    public VariantService() {
        this.layoutResolver = this.projectService::resolveLayout;
        this.projectCacheInvalidator = server -> HistoryCaptureManager.getInstance().invalidateProjectCache(server);
    }

    VariantService(ProjectLayoutResolver layoutResolver, ProjectCacheInvalidator projectCacheInvalidator) {
        this.layoutResolver = layoutResolver;
        this.projectCacheInvalidator = projectCacheInvalidator;
    }

    public List<ProjectVariant> listVariants(MinecraftServer server, String projectName) throws IOException {
        return this.variantRepository.loadAll(this.layoutResolver.resolveLayout(server, projectName));
    }

    /**
     * Creates a new variant from the supplied version or the active head.
     *
     * <p>Creation only writes branch metadata. It deliberately leaves any live
     * recovery draft untouched; switching to the new branch is the workflow that
     * freezes and validates pending world edits.
     */
    public ProjectVariant createVariant(MinecraftServer server, String projectName, String variantName, String fromVersionId) throws IOException {
        if (variantName == null || variantName.isBlank()) {
            throw new IllegalArgumentException("Variant name is required");
        }

        String displayName = variantName.trim();
        ProjectLayout layout = this.layoutResolver.resolveLayout(server, projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));

        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        var tombstones = this.tombstoneRepository.load(layout);
        List<ProjectVariant> visibleVariants = variants.stream()
                .filter(variant -> !tombstones.variantDeleted(variant.id()))
                .toList();
        String baseVersionId = fromVersionId;
        if (baseVersionId == null || baseVersionId.isBlank()) {
            ProjectVariant activeVariant = visibleVariants.stream()
                    .filter(variant -> variant.id().equals(project.activeVariantId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Active variant is missing"));
            baseVersionId = activeVariant.headVersionId();
        } else if (this.versionRepository.load(layout, baseVersionId).isEmpty()) {
            throw new IllegalArgumentException("Version not found: " + baseVersionId);
        }

        if (this.variantNameExists(visibleVariants, displayName)) {
            throw new IllegalArgumentException("Variant already exists: " + displayName);
        }

        String variantId = this.uniqueVariantId(displayName, variants);
        ProjectVariant variant = new ProjectVariant(
                variantId,
                displayName,
                baseVersionId,
                baseVersionId,
                false,
                Instant.now(),
                ProjectVariantSwitchKeys.firstAvailableDefaultKey(visibleVariants)
        );
        List<ProjectVariant> nextVariants = new ArrayList<>(variants);
        nextVariants.add(variant);
        this.variantRepository.save(layout, nextVariants);
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "variant-created",
                "Created variant " + variantId,
                baseVersionId,
                variantId
        ));
        this.projectCacheInvalidator.invalidate(server);
        return variant;
    }

    public List<ProjectVariant> setVariantSwitchKey(
            MinecraftServer server,
            String projectName,
            String variantId,
            String switchKey
    ) throws IOException {
        ProjectLayout layout = this.layoutResolver.resolveLayout(server, projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        List<ProjectVariant> variants = ProjectVariantSwitchKeys.assign(
                this.variantRepository.loadAll(layout),
                variantId,
                switchKey
        );
        Instant now = Instant.now();
        this.variantRepository.save(layout, variants);
        this.projectRepository.save(layout, project.withUpdatedAt(now).withSchemaVersion(io.github.luma.domain.model.BuildProject.CURRENT_SCHEMA_VERSION));
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "variant-switch-key-updated",
                "Updated variant switch key for " + variantId,
                "",
                variantId
        ));
        this.projectCacheInvalidator.invalidate(server);
        return variants;
    }

    public ProjectVariant switchVariant(ServerLevel level, String projectName, String variantId) throws IOException {
        ProjectLayout layout = this.layoutResolver.resolveLayout(level.getServer(), projectName);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVariant targetVariant = variants.stream()
                .filter(variant -> variant.id().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + variantId));

        if (targetVariant.headVersionId() == null || targetVariant.headVersionId().isBlank()) {
            throw new IllegalArgumentException("Variant head version is missing: " + variantId);
        }

        this.restoreService.restoreVariantHead(level, projectName, targetVariant.id());
        return targetVariant;
    }

    private String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "variant" : slug;
    }

    private String uniqueVariantId(String variantName, List<ProjectVariant> variants) {
        String baseId = this.slug(variantName);
        String candidateId = baseId;
        int suffix = 2;
        while (this.variantIdExists(variants, candidateId)) {
            candidateId = baseId + "-" + suffix;
            suffix += 1;
        }
        return candidateId;
    }

    private boolean variantIdExists(List<ProjectVariant> variants, String variantId) {
        return variants.stream().anyMatch(variant -> variant.id().equals(variantId));
    }

    private boolean variantNameExists(List<ProjectVariant> variants, String variantName) {
        return variants.stream()
                .map(ProjectVariant::name)
                .filter(name -> name != null)
                .anyMatch(name -> name.trim().equalsIgnoreCase(variantName));
    }

    interface ProjectLayoutResolver {

        ProjectLayout resolveLayout(MinecraftServer server, String projectName) throws IOException;
    }

    interface ProjectCacheInvalidator {

        void invalidate(MinecraftServer server);
    }
}
