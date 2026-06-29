package io.github.luma.domain.model;

import java.time.Instant;

public record ProjectVariant(
        String id,
        String name,
        String baseVersionId,
        String headVersionId,
        boolean main,
        Instant createdAt,
        String switchKey
) {

    public ProjectVariant(
            String id,
            String name,
            String baseVersionId,
            String headVersionId,
            boolean main,
            Instant createdAt
    ) {
        this(id, name, baseVersionId, headVersionId, main, createdAt, null);
    }

    public ProjectVariant {
        switchKey = switchKey == null ? null : ProjectVariantSwitchKeys.normalize(switchKey);
    }

    public static ProjectVariant main(String headVersionId, Instant now) {
        return new ProjectVariant("main", "main", headVersionId, headVersionId, true, now, ProjectVariantSwitchKeys.defaultKey(0));
    }

    public ProjectVariant withSwitchKey(String switchKey) {
        return new ProjectVariant(
                this.id,
                this.name,
                this.baseVersionId,
                this.headVersionId,
                this.main,
                this.createdAt,
                switchKey
        );
    }
}
