package io.github.luma.domain.model;

public record ProjectArchiveEntry(
        String path,
        long size,
        boolean optional
) {
}
