package io.github.luma.ui.state;

public record DashboardProjectItem(
        String name,
        String activeVariantId,
        int versionCount,
        boolean hasDraft
) {
}
