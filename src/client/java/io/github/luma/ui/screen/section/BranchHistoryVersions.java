package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.ProjectUiSupport;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class BranchHistoryVersions {

    List<Entry> forVariant(List<ProjectVersion> versions, List<ProjectVariant> variants, ProjectVariant variant) {
        if (variant == null || versions == null) {
            return List.of();
        }

        Set<String> visibleVersionIds = new LinkedHashSet<>();
        for (ProjectVersion version : versions) {
            if (version != null && variant.id().equals(version.variantId())) {
                visibleVersionIds.add(version.id());
            }
        }
        this.addVersionId(visibleVersionIds, variant.headVersionId());
        this.addVersionId(visibleVersionIds, variant.baseVersionId());

        return visibleVersionIds.stream()
                .map(versionId -> this.entryFor(versions, variants, variant, versionId))
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
            String versionId
    ) {
        ProjectVersion version = ProjectUiSupport.versionFor(versions, versionId);
        if (version == null) {
            return null;
        }
        boolean selectedBranchVersion = selectedVariant.id().equals(version.variantId())
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

    record Entry(ProjectVersion version, ProjectVariant variant, boolean current) {
    }
}
