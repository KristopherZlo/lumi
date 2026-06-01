package io.github.luma.client.update;

import java.util.Optional;

public record UpdateProjectNotice(
        String version,
        String minecraftVersion,
        String title,
        String summary,
        String downloadUrl
) {

    public UpdateProjectNotice {
        version = normalize(version);
        minecraftVersion = normalize(minecraftVersion);
        title = normalize(title);
        summary = normalize(summary);
        downloadUrl = normalize(downloadUrl);
    }

    public static Optional<UpdateProjectNotice> from(Optional<UpdateRelease> promptRelease) {
        return promptRelease.flatMap(UpdateProjectNotice::from);
    }

    public static Optional<UpdateProjectNotice> from(UpdateRelease release) {
        if (release == null || release.version().isBlank() || release.downloadUrl().isBlank()) {
            return Optional.empty();
        }
        String minecraftVersion = release.minecraftVersions().isEmpty() ? "" : release.minecraftVersions().getFirst();
        return Optional.of(new UpdateProjectNotice(
                release.version(),
                minecraftVersion,
                release.title(),
                release.summary(),
                release.downloadUrl()
        ));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
