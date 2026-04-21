package io.github.luma.ui.controller;

import io.github.luma.domain.model.MaterialDeltaEntry;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionDiff;
import io.github.luma.domain.service.DiffService;
import io.github.luma.domain.service.MaterialDeltaService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.VersionLineageService;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.state.CompareViewState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class CompareScreenController {

    public static final String CURRENT_WORLD_REFERENCE = "current";

    private final Minecraft client = Minecraft.getInstance();
    private final DiffService diffService = new DiffService();
    private final MaterialDeltaService materialDeltaService = new MaterialDeltaService();
    private final ProjectService projectService = new ProjectService();
    private final VersionLineageService versionLineageService = new VersionLineageService();

    public static boolean isCurrentWorldReference(String reference) {
        if (reference == null) {
            return false;
        }

        String normalized = reference.trim().toLowerCase();
        return normalized.equals(CURRENT_WORLD_REFERENCE)
                || normalized.equals("current-world")
                || normalized.equals("current world")
                || normalized.equals("live")
                || normalized.equals("world")
                || normalized.equals("текущий")
                || normalized.equals("текущий мир")
                || normalized.equals("мир");
    }

    public CompareViewState loadState(String projectName, String leftReference, String rightReference, String status) {
        if (!this.client.hasSingleplayerServer()) {
            return new CompareViewState(
                    List.of(),
                    List.of(),
                    "",
                    leftReference,
                    rightReference,
                    "",
                    "",
                    null,
                    List.of(),
                    "luma.status.singleplayer_only",
                    LumaDebugLog.globalEnabled()
            );
        }

        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            var variants = new ArrayList<>(this.diffService.listVariants(server, projectName));
            var versions = new ArrayList<>(this.versionLineageService.reachableVersions(
                    this.diffService.listVersions(server, projectName),
                    variants
            ));
            versions.sort(Comparator.comparing(ProjectVersion::createdAt).reversed());
            var project = this.projectService.loadProject(server, projectName);
            boolean debugEnabled = LumaDebugLog.enabled(project);
            String activeVariantId = project.activeVariantId();
            String activeHeadVersionId = this.activeHeadVersionId(variants, activeVariantId);

            String resolvedRight = this.resolveReference(versions, variants, rightReference);
            if (resolvedRight.isBlank() && !activeHeadVersionId.isBlank()) {
                resolvedRight = activeHeadVersionId;
            }
            if (resolvedRight.isBlank() && !versions.isEmpty()) {
                resolvedRight = versions.getFirst().id();
            }

            String resolvedLeft = this.resolveReference(versions, variants, leftReference);
            if (resolvedLeft.isBlank() && CURRENT_WORLD_REFERENCE.equals(resolvedRight)) {
                resolvedLeft = activeHeadVersionId;
            }
            if (resolvedLeft.isBlank() && !resolvedRight.isBlank() && !CURRENT_WORLD_REFERENCE.equals(resolvedRight)) {
                resolvedLeft = this.parentOrPrevious(versions, resolvedRight);
            }

            LumaDebugLog.log(
                    project,
                    "compare",
                    "Resolved compare request for {}: leftInput='{}' -> '{}' | rightInput='{}' -> '{}' | versions={} | variants={}",
                    projectName,
                    leftReference,
                    resolvedLeft,
                    rightReference,
                    resolvedRight,
                    versions.size(),
                    variants.size()
            );

            VersionDiff diff;
            if (resolvedLeft.isBlank() || resolvedRight.isBlank() || (
                    CURRENT_WORLD_REFERENCE.equals(resolvedLeft) && CURRENT_WORLD_REFERENCE.equals(resolvedRight)
            )) {
                diff = null;
            } else if (CURRENT_WORLD_REFERENCE.equals(resolvedRight)) {
                diff = this.diffService.compareVersionToCurrentState(server, projectName, resolvedLeft);
            } else if (CURRENT_WORLD_REFERENCE.equals(resolvedLeft)) {
                diff = this.invert(this.diffService.compareVersionToCurrentState(server, projectName, resolvedRight));
            } else {
                diff = this.diffService.compareVersions(server, projectName, resolvedLeft, resolvedRight);
            }
            List<MaterialDeltaEntry> materialDelta = diff == null ? List.of() : this.materialDeltaService.summarize(diff);
            if (diff != null) {
                LumaDebugLog.log(
                        project,
                        "compare",
                        "Built compare diff {} -> {} with {} changed blocks and {} changed chunks",
                        diff.leftVersionId(),
                        diff.rightVersionId(),
                        diff.changedBlockCount(),
                        diff.changedChunks()
                );
            }

            return new CompareViewState(
                    versions,
                    variants,
                    activeVariantId,
                    leftReference == null ? "" : leftReference,
                    rightReference == null ? "" : rightReference,
                    resolvedLeft,
                    resolvedRight,
                    diff,
                    materialDelta,
                    status == null || status.isBlank() ? "luma.status.compare_ready" : status,
                    debugEnabled
            );
        } catch (Exception exception) {
            return new CompareViewState(
                    List.of(),
                    List.of(),
                    "",
                    leftReference,
                    rightReference,
                    "",
                    "",
                    null,
                    List.of(),
                    "luma.status.compare_failed",
                    LumaDebugLog.globalEnabled()
            );
        }
    }

    public String showOverlay(CompareViewState state) {
        if (state.diff() == null) {
            return "luma.status.compare_failed";
        }

        CompareOverlayRenderer.show(
                state.leftResolvedVersionId(),
                state.rightResolvedVersionId(),
                state.diff().changedBlocks(),
                state.debugEnabled()
        );
        return "luma.status.compare_overlay_enabled";
    }

    public String clearOverlay() {
        CompareOverlayRenderer.clear();
        return "luma.status.compare_overlay_cleared";
    }

    public String toggleOverlayVisibility() {
        if (!CompareOverlayRenderer.hasData()) {
            return "luma.status.compare_failed";
        }
        return CompareOverlayRenderer.toggleVisibility()
                ? "luma.status.compare_overlay_enabled"
                : "luma.status.compare_overlay_hidden";
    }

    private String resolveReference(List<ProjectVersion> versions, List<ProjectVariant> variants, String reference) {
        if (reference == null || reference.isBlank()) {
            return "";
        }

        if (isCurrentWorldReference(reference)) {
            return CURRENT_WORLD_REFERENCE;
        }

        for (ProjectVariant variant : variants) {
            if (variant.id().equalsIgnoreCase(reference) || variant.name().equalsIgnoreCase(reference)) {
                return variant.headVersionId();
            }
        }

        for (ProjectVersion version : versions) {
            if (version.id().equalsIgnoreCase(reference)) {
                return version.id();
            }
        }

        return "";
    }

    private String activeHeadVersionId(List<ProjectVariant> variants, String activeVariantId) {
        for (ProjectVariant variant : variants) {
            if (variant.id().equals(activeVariantId)) {
                return variant.headVersionId();
            }
        }
        return "";
    }

    private String parentOrPrevious(List<ProjectVersion> versions, String versionId) {
        for (int index = 0; index < versions.size(); index++) {
            ProjectVersion version = versions.get(index);
            if (!version.id().equals(versionId)) {
                continue;
            }

            if (version.parentVersionId() != null && !version.parentVersionId().isBlank()) {
                return version.parentVersionId();
            }

            return index + 1 < versions.size() ? versions.get(index + 1).id() : "";
        }

        return "";
    }

    private VersionDiff invert(VersionDiff diff) {
        var changedBlocks = diff.changedBlocks().stream()
                .map(entry -> new io.github.luma.domain.model.DiffBlockEntry(
                        entry.pos(),
                        entry.rightState(),
                        entry.leftState(),
                        switch (entry.changeType()) {
                            case ADDED -> io.github.luma.domain.model.ChangeType.REMOVED;
                            case REMOVED -> io.github.luma.domain.model.ChangeType.ADDED;
                            case CHANGED -> io.github.luma.domain.model.ChangeType.CHANGED;
                        }
                ))
                .toList();
        return new VersionDiff(CURRENT_WORLD_REFERENCE, diff.leftVersionId(), changedBlocks, diff.changedChunks());
    }
}
