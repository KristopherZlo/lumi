package io.github.luma.domain.model;

import java.time.Instant;

public record ProjectVariant(
        String id,
        String name,
        String baseVersionId,
        String headVersionId,
        boolean main,
        Instant createdAt
) {

    public static ProjectVariant main(String headVersionId, Instant now) {
        return new ProjectVariant("main", "main", headVersionId, headVersionId, true, now);
    }
}
