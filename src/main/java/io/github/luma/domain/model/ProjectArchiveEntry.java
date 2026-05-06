package io.github.luma.domain.model;

public record ProjectArchiveEntry(
        String path,
        long size,
        boolean optional,
        String sha256Hex
) {

    public ProjectArchiveEntry(String path, long size, boolean optional) {
        this(path, size, optional, "");
    }

    public ProjectArchiveEntry {
        sha256Hex = sha256Hex == null ? "" : sha256Hex;
    }

    public boolean hasSha256() {
        return !this.sha256Hex.isBlank();
    }
}
