package io.github.luma.ui.controller;

import io.github.luma.domain.model.MaterialDeltaEntry;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionDiff;
import io.github.luma.domain.service.DiffService;
import io.github.luma.domain.service.MaterialDeltaService;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.state.CompareViewState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class CompareScreenController {

    private final Minecraft client = Minecraft.getInstance();
    private final DiffService diffService = new DiffService();
    private final MaterialDeltaService materialDeltaService = new MaterialDeltaService();

    public CompareViewState loadState(String projectName, String leftReference, String rightReference, String status) {
        if (!this.client.hasSingleplayerServer()) {
            return new CompareViewState(
                    List.of(),
                    List.of(),
                    leftReference,
                    rightReference,
                    "",
                    "",
                    null,
                    List.of(),
                    "luma.status.singleplayer_only"
            );
        }

        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            var versions = new ArrayList<>(this.diffService.listVersions(server, projectName));
            versions.sort(Comparator.comparing(ProjectVersion::createdAt).reversed());
            var variants = new ArrayList<>(this.diffService.listVariants(server, projectName));

            String resolvedRight = this.resolveReference(versions, variants, rightReference);
            if (resolvedRight.isBlank() && !versions.isEmpty()) {
                resolvedRight = versions.getFirst().id();
            }

            String resolvedLeft = this.resolveReference(versions, variants, leftReference);
            if (resolvedLeft.isBlank() && !resolvedRight.isBlank()) {
                resolvedLeft = this.parentOrPrevious(versions, resolvedRight);
            }

            VersionDiff diff = resolvedLeft.isBlank() || resolvedRight.isBlank()
                    ? null
                    : this.diffService.compareVersions(server, projectName, resolvedLeft, resolvedRight);
            List<MaterialDeltaEntry> materialDelta = diff == null ? List.of() : this.materialDeltaService.summarize(diff);

            return new CompareViewState(
                    versions,
                    variants,
                    leftReference == null ? "" : leftReference,
                    rightReference == null ? "" : rightReference,
                    resolvedLeft,
                    resolvedRight,
                    diff,
                    materialDelta,
                    status == null || status.isBlank() ? "luma.status.compare_ready" : status
            );
        } catch (Exception exception) {
            return new CompareViewState(
                    List.of(),
                    List.of(),
                    leftReference,
                    rightReference,
                    "",
                    "",
                    null,
                    List.of(),
                    "luma.status.compare_failed"
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
                state.diff().changedBlocks()
        );
        return "luma.status.compare_overlay_enabled";
    }

    public String clearOverlay() {
        CompareOverlayRenderer.clear();
        return "luma.status.compare_overlay_cleared";
    }

    private String resolveReference(List<ProjectVersion> versions, List<ProjectVariant> variants, String reference) {
        if (reference == null || reference.isBlank()) {
            return "";
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
}
