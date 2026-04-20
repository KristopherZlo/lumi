package io.github.luma.domain.service;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.storage.ProjectLayout;
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
 * recovery draft exists, and optionally restores the target head when switching
 * variants.
 */
public final class VariantService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final RestoreService restoreService = new RestoreService();

    public List<ProjectVariant> listVariants(MinecraftServer server, String projectName) throws IOException {
        return this.variantRepository.loadAll(this.projectService.resolveLayout(server, projectName));
    }

    /**
     * Creates a new variant from the supplied version or the active head.
     */
    public ProjectVariant createVariant(MinecraftServer server, String projectName, String variantName, String fromVersionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        HistoryCaptureManager.getInstance().finalizeProjectSession(server, project.id().toString());
        if (this.recoveryRepository.loadDraft(layout).isPresent()) {
            throw new IllegalArgumentException("Discard or save the current recovery draft before creating a variant");
        }

        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        String baseVersionId = fromVersionId;
        if (baseVersionId == null || baseVersionId.isBlank()) {
            ProjectVariant activeVariant = variants.stream()
                    .filter(variant -> variant.id().equals(project.activeVariantId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Active variant is missing"));
            baseVersionId = activeVariant.headVersionId();
        } else if (this.versionRepository.load(layout, baseVersionId).isEmpty()) {
            throw new IllegalArgumentException("Version not found: " + baseVersionId);
        }

        String variantId = this.slug(variantName);
        if (variants.stream().anyMatch(variant -> variant.id().equals(variantId))) {
            throw new IllegalArgumentException("Variant already exists: " + variantId);
        }

        ProjectVariant variant = new ProjectVariant(variantId, variantName, baseVersionId, baseVersionId, false, Instant.now());
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
        HistoryCaptureManager.getInstance().invalidateProjectCache(server);
        return variant;
    }

    public ProjectVariant switchVariant(ServerLevel level, String projectName, String variantId) throws IOException {
        return this.switchVariant(level, projectName, variantId, true);
    }

    /**
     * Switches the active variant and optionally restores that variant head into
     * the world.
     */
    public ProjectVariant switchVariant(ServerLevel level, String projectName, String variantId, boolean restoreHead) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        HistoryCaptureManager.getInstance().finalizeProjectSession(level.getServer(), project.id().toString());
        if (this.recoveryRepository.loadDraft(layout).isPresent()) {
            throw new IllegalArgumentException("Discard or save the current recovery draft before switching variants");
        }

        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVariant targetVariant = variants.stream()
                .filter(variant -> variant.id().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + variantId));

        this.projectRepository.save(layout, project.withActiveVariantId(targetVariant.id(), Instant.now()).withSchemaVersion(io.github.luma.domain.model.BuildProject.CURRENT_SCHEMA_VERSION));
        if (restoreHead && targetVariant.headVersionId() != null && !targetVariant.headVersionId().isBlank()) {
            this.restoreService.restore(level, projectName, targetVariant.headVersionId());
        }
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "variant-switched",
                "Switched active variant to " + targetVariant.id(),
                targetVariant.headVersionId(),
                targetVariant.id()
        ));
        HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
        return targetVariant;
    }

    private String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "variant" : slug;
    }
}
