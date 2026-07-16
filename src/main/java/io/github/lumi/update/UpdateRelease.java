package io.github.lumi.update;

import java.net.URI;
import java.util.Objects;

public record UpdateRelease(
        String version,
        String minecraftVersion,
        String summary,
        URI downloadUri) {
    public UpdateRelease {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(downloadUri, "downloadUri");
    }
}
