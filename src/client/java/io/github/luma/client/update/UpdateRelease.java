package io.github.luma.client.update;

import java.util.List;

public record UpdateRelease(
        String version,
        int versionCode,
        List<String> minecraftVersions,
        String loader,
        String channel,
        String title,
        String summary,
        String downloadUrl,
        String changelogUrl,
        String sha256
) {

    public UpdateRelease {
        version = normalize(version);
        minecraftVersions = minecraftVersions == null ? List.of() : minecraftVersions.stream()
                .map(UpdateRelease::normalize)
                .filter(value -> !value.isBlank())
                .toList();
        loader = normalize(loader);
        channel = normalize(channel);
        title = normalize(title);
        summary = normalize(summary);
        downloadUrl = normalize(downloadUrl);
        changelogUrl = normalize(changelogUrl);
        sha256 = normalize(sha256);
    }

    boolean supportsMinecraft(String minecraftVersion) {
        String normalized = normalize(minecraftVersion);
        return !normalized.isBlank() && this.minecraftVersions.contains(normalized);
    }

    boolean supportsLoader(String loader) {
        String normalized = normalize(loader);
        return this.loader.isBlank() || this.loader.equalsIgnoreCase(normalized);
    }

    boolean isPromptableChannel() {
        return this.channel.isBlank()
                || this.channel.equalsIgnoreCase("stable")
                || this.channel.equalsIgnoreCase("alpha");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
