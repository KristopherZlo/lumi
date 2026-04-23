package io.github.luma.domain.model;

public record ProjectArchiveScope(
        ProjectArchiveScopeType type,
        String variantId,
        String variantName,
        String baseVersionId,
        String headVersionId
) {

    public static ProjectArchiveScope project() {
        return new ProjectArchiveScope(ProjectArchiveScopeType.PROJECT, "", "", "", "");
    }

    public static ProjectArchiveScope variant(ProjectVariant variant) {
        return new ProjectArchiveScope(
                ProjectArchiveScopeType.VARIANT,
                variant.id(),
                variant.name(),
                variant.baseVersionId(),
                variant.headVersionId()
        );
    }

    public boolean projectScope() {
        return this.type == ProjectArchiveScopeType.PROJECT;
    }

    public boolean variantScope() {
        return this.type == ProjectArchiveScopeType.VARIANT;
    }
}
