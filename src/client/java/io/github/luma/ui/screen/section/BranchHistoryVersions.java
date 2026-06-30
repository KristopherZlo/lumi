package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.service.VersionLineageService;
import io.github.luma.ui.ProjectUiSupport;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BranchHistoryVersions {

    private final VersionLineageService lineageService = new VersionLineageService();

    public List<Entry> forVariant(List<ProjectVersion> versions, List<ProjectVariant> variants, ProjectVariant variant) {
        if (variant == null || versions == null) {
            return List.of();
        }

        Set<String> selectedLineageVersionIds = this.lineageService.reachableVersionIds(versions, variant.headVersionId());
        Set<String> visibleVersionIds = new LinkedHashSet<>();
        for (ProjectVersion version : versions) {
            if (this.visibleCommit(version) && variant.id().equals(version.variantId())) {
                visibleVersionIds.add(version.id());
            }
        }
        visibleVersionIds.addAll(selectedLineageVersionIds);
        this.addVersionId(visibleVersionIds, variant.headVersionId());
        this.addVersionId(visibleVersionIds, variant.baseVersionId());

        return visibleVersionIds.stream()
                .map(versionId -> this.entryFor(versions, variants, variant, selectedLineageVersionIds, versionId))
                .filter(entry -> entry != null)
                .sorted(Comparator
                        .comparing((Entry entry) -> !entry.current())
                        .thenComparing(entry -> entry.version().createdAt(), Comparator.reverseOrder()))
                .toList();
    }

    private void addVersionId(Set<String> versionIds, String versionId) {
        if (versionId != null && !versionId.isBlank()) {
            versionIds.add(versionId);
        }
    }

    private Entry entryFor(
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVariant selectedVariant,
            Set<String> selectedLineageVersionIds,
            String versionId
    ) {
        ProjectVersion version = ProjectUiSupport.versionFor(versions, versionId);
        if (!this.visibleCommit(version)) {
            return null;
        }
        boolean selectedBranchVersion = selectedVariant.id().equals(version.variantId())
                || selectedLineageVersionIds.contains(version.id())
                || version.id().equals(selectedVariant.headVersionId())
                || version.id().equals(selectedVariant.baseVersionId());
        ProjectVariant entryVariant = selectedBranchVersion
                ? selectedVariant
                : ProjectUiSupport.variantFor(variants == null ? List.of() : variants, version.variantId());
        if (entryVariant == null) {
            return null;
        }
        return new Entry(version, entryVariant, version.id().equals(selectedVariant.headVersionId()));
    }

    private boolean visibleCommit(ProjectVersion version) {
        return version != null && version.versionKind() != VersionKind.RESTORE;
    }

    public record Entry(ProjectVersion version, ProjectVariant variant, boolean current) {
    }
}
