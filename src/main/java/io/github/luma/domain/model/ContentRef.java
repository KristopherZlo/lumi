package io.github.luma.domain.model;

public record ContentRef(
        String sha256,
        String logicalKind,
        long uncompressedBytes,
        long compressedBytes
) {

    public ContentRef {
        sha256 = sha256 == null ? "" : sha256;
        logicalKind = logicalKind == null ? "" : logicalKind;
    }
}
