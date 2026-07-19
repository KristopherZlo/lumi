package io.github.lumi.storage.packageformat;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
/** Bounded canonical inventory for one portable Lumi commit tree. */
public record LumiPackageManifest(
        String dimensionId, CommitId commit, int commitBytes,
        Map<ObjectId, Integer> objects, Optional<Preview> preview) {
    public static final int MAX_ENTRY_BYTES = 256 * 1024 * 1024;
    public static final int MAX_COMMIT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_PREVIEW_BYTES = 4 * 1024 * 1024;
    public static final int MAX_OBJECTS = 1_000_000;
    public static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024;
    private static final int MAX_DIMENSION_BYTES = 256;
    public LumiPackageManifest(String dimensionId, CommitId commit,
            int commitBytes, Map<ObjectId, Integer> objects) {
        this(dimensionId, commit, commitBytes, objects, Optional.empty());
    }
    public LumiPackageManifest {
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(commit, "commit");
        objects = Map.copyOf(Objects.requireNonNull(objects, "objects"));
        preview = Objects.requireNonNull(preview, "preview");
        int dimensionBytes = dimensionId.getBytes(StandardCharsets.UTF_8).length;
        if (dimensionId.isBlank() || dimensionBytes > MAX_DIMENSION_BYTES) {
            throw new IllegalArgumentException("Invalid package dimension ID");
        }
        if (commitBytes < 1 || commitBytes > MAX_COMMIT_BYTES) {
            throw new IllegalArgumentException("Invalid package commit size");
        }
        if (objects.size() > MAX_OBJECTS) {
            throw new IllegalArgumentException("Package has too many objects");
        }
        long total = Math.addExact(commitBytes, preview.map(Preview::bytes).orElse(0));
        for (var entry : objects.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "object ID");
            Integer size = Objects.requireNonNull(entry.getValue(), "object size");
            if (size < 1 || size > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("Invalid package object size");
            }
            total = Math.addExact(total, size.longValue());
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("Package uncompressed size is too large");
        }
    }
    public long totalBytes() {
        return objects.values().stream().mapToLong(Integer::longValue).sum()
                + commitBytes + preview.map(Preview::bytes).orElse(0);
    }
    /** Integrity metadata for the optional non-authoritative PNG. */
    public record Preview(ObjectId hash, int bytes) {
        public Preview {
            Objects.requireNonNull(hash, "hash");
            if (bytes < 1 || bytes > MAX_PREVIEW_BYTES) {
                throw new IllegalArgumentException("Invalid package preview size");
            }
        }
    }
}
