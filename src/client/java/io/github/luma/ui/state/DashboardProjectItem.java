package io.github.luma.ui.state;

public record DashboardProjectItem(
        String name,
        String activeVariantId,
        int versionCount,
        boolean hasDraft,
        boolean favorite,
        boolean archived,
        String updatedAt
) {
}
