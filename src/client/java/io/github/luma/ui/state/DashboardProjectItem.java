package io.github.luma.ui.state;

public record DashboardProjectItem(
        String name,
        String dimensionId,
        String activeVariantId,
        int versionCount,
        int branchCount,
        int draftChangeCount,
        boolean hasDraft,
        boolean worldWorkspace,
        boolean favorite,
        boolean archived,
        String updatedAt
) {
}
