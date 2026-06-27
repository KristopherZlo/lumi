package io.github.luma.ui.controller;

import io.github.luma.domain.model.MaterialDeltaEntry;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionDiff;
import io.github.luma.domain.service.DiffService;
import io.github.luma.domain.service.MaterialDeltaService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.ui.overlay.CompareOverlayPreparationService;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.state.CompareLoadState;
import io.github.luma.ui.state.CompareViewState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

public final class CompareScreenController {

    public static final String CURRENT_WORLD_REFERENCE = "current";

    private final Minecraft client = Minecraft.getInstance();
    private final DiffService diffService = new DiffService();
    private final MaterialDeltaService materialDeltaService = new MaterialDeltaService();
    private final ProjectService projectService = new ProjectService();
    private final AsyncCompareCache asyncCompareCache = AsyncCompareCache.getInstance();

    public static boolean isCurrentWorldReference(String reference) {
        if (reference == null) {
            return false;
        }

        String normalized = reference.trim().toLowerCase();
        if (normalized.equals("\u0442\u0435\u043a\u0443\u0449\u0438\u0439")
                || normalized.equals("\u0442\u0435\u043a\u0443\u0449\u0438\u0439 \u043c\u0438\u0440")
                || normalized.equals("\u043c\u0438\u0440")) {
            return true;
        }
        return normalized.equals(CURRENT_WORLD_REFERENCE)
                || normalized.equals("current-world")
                || normalized.equals("current world")
                || normalized.equals("live")
                || normalized.equals("world")
                || normalized.equals("текущий")
                || normalized.equals("текущий мир")
                || normalized.equals("мир")
                || normalized.equals("текущий")
                || normalized.equals("текущий мир")
                || normalized.equals("мир");
    }

    public CompareViewState loadState(String projectName, String leftReference, String rightReference, String status) {
        return this.loadState(projectName, leftReference, rightReference, status, false);
    }

    public CompareViewState loadState(
            String projectName,
            String leftReference,
            String rightReference,
            String status,
            boolean refresh
    ) {
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
                    LumaDebugLog.globalEnabled(),
                    CompareLoadState.FAILED
            );
        }

        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            var variants = new ArrayList<>(this.diffService.listVariants(server, projectName));
            var versions = new ArrayList<>(this.diffService.listVersions(server, projectName));
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

            String finalResolvedLeft = resolvedLeft;
            String finalResolvedRight = resolvedRight;
            CompareRequestKey requestKey = new CompareRequestKey(projectName, finalResolvedLeft, finalResolvedRight);
            AsyncCompareCache.CompareResultState asyncState = this.asyncCompareCache.request(
                    requestKey,
                    () -> {
                        VersionDiff diff = this.buildDiff(server, projectName, finalResolvedLeft, finalResolvedRight);
                        List<MaterialDeltaEntry> materialDelta = diff == null
                                ? List.of()
                                : this.materialDeltaService.summarize(diff);
                        return new AsyncCompareCache.CompareResult(diff, materialDelta);
                    },
                    refresh
            );
            if (asyncState.status() == AsyncCompareCache.Status.LOADING) {
                return new CompareViewState(
                        versions,
                        variants,
                        activeVariantId,
                        leftReference == null ? "" : leftReference,
                        rightReference == null ? "" : rightReference,
                        resolvedLeft,
                        resolvedRight,
                        null,
                        List.of(),
                        "luma.status.compare_loading",
                        debugEnabled,
                        CompareLoadState.LOADING
                );
            }
            if (asyncState.status() == AsyncCompareCache.Status.FAILED) {
                LumaDebugLog.log(
                        project,
                        "compare",
                        "Failed compare diff {} -> {} with {}: {}",
                        resolvedLeft,
                        resolvedRight,
                        asyncState.failure() == null ? "unknown" : asyncState.failure().getClass().getSimpleName(),
                        asyncState.failure() == null ? "" : asyncState.failure().getMessage()
                );
                return new CompareViewState(
                        versions,
                        variants,
                        activeVariantId,
                        leftReference == null ? "" : leftReference,
                        rightReference == null ? "" : rightReference,
                        resolvedLeft,
                        resolvedRight,
                        null,
                        List.of(),
                        "luma.status.compare_failed",
                        debugEnabled,
                        CompareLoadState.FAILED
                );
            }

            VersionDiff diff = asyncState.result() == null ? null : asyncState.result().diff();
            List<MaterialDeltaEntry> materialDelta = asyncState.result() == null
                    ? List.of()
                    : asyncState.result().materialDelta();
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
                    debugEnabled,
                    CompareLoadState.READY
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
                    LumaDebugLog.globalEnabled(),
                    CompareLoadState.FAILED
            );
        }
    }

    public VersionDiff buildDiff(
            MinecraftServer server,
            String projectName,
            String resolvedLeft,
            String resolvedRight
    ) throws IOException {
        if (resolvedLeft == null || resolvedRight == null) {
            return null;
        }
        if (resolvedLeft.isBlank() || resolvedRight.isBlank() || (
                CURRENT_WORLD_REFERENCE.equals(resolvedLeft) && CURRENT_WORLD_REFERENCE.equals(resolvedRight)
        )) {
            return null;
        }
        if (CURRENT_WORLD_REFERENCE.equals(resolvedRight)) {
            return this.diffService.compareVersionToCurrentState(server, projectName, resolvedLeft);
        }
        if (CURRENT_WORLD_REFERENCE.equals(resolvedLeft)) {
            return this.invert(this.diffService.compareVersionToCurrentState(server, projectName, resolvedRight));
        }
        return this.diffService.compareVersions(server, projectName, resolvedLeft, resolvedRight);
    }

    public String showOverlay(String projectName, CompareViewState state) {
        if (state.diff() == null) {
            return "luma.status.compare_failed";
        }
        if (state.diff().changedBlocks().isEmpty()) {
            CompareOverlayRenderer.clear();
            return "luma.status.compare_no_changes";
        }

        if (CompareOverlayRenderer.shouldPrepareInBackground(state.diff().changedBlocks())) {
            CompareOverlayPreparationService.getInstance().prepareAndShow(
                    projectName,
                    state.leftResolvedVersionId(),
                    state.rightResolvedVersionId(),
                    state.diff().changedBlocks(),
                    state.debugEnabled()
            );
            return "luma.status.compare_overlay_loading";
        }

        CompareOverlayRenderer.show(
                projectName,
                state.leftResolvedVersionId(),
                state.rightResolvedVersionId(),
                state.diff().changedBlocks(),
                state.debugEnabled()
        );
        return "luma.status.compare_overlay_enabled";
    }

    public String clearOverlay() {
        CompareOverlayPreparationService.getInstance().cancelPending();
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
                        },
                        entry.rightBlockId(),
                        entry.leftBlockId()
                ))
                .toList();
        return new VersionDiff(CURRENT_WORLD_REFERENCE, diff.leftVersionId(), changedBlocks, diff.changedChunks());
    }
}
