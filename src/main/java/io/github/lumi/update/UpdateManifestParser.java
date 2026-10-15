package io.github.lumi.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Optional;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.VersionParsingException;

/** Strict parser for Lumi's small public release manifest. */
public final class UpdateManifestParser {
    public static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_RELEASES = 128;

    public UpdateCheckResult parse(
            byte[] content, String currentVersion, String minecraftVersion) {
        if (content.length > MAX_BYTES) {
            throw new IllegalArgumentException("Update manifest is too large");
        }
        try {
            JsonObject root = JsonParser.parseString(
                    new String(content, StandardCharsets.UTF_8)).getAsJsonObject();
            require(root.get("schema").getAsInt() == 1, "Unsupported update schema");
            require("lumi".equals(text(root, "modId", 16)), "Wrong update mod id");
            var versions = root.getAsJsonArray("versions");
            require(versions != null && versions.size() <= MAX_RELEASES,
                    "Invalid update release count");
            SemanticVersion current = parseVersion(currentVersion);
            Optional<Candidate> newest = versions.asList().stream()
                    .map(element -> candidate(element.getAsJsonObject(), minecraftVersion))
                    .flatMap(Optional::stream)
                    .max(Comparator.comparing(Candidate::number));
            if (newest.isEmpty() || newest.orElseThrow().number().compareTo(current) <= 0) {
                return UpdateCheckResult.upToDate();
            }
            return UpdateCheckResult.available(newest.orElseThrow().release());
        } catch (JsonParseException | IllegalStateException | NullPointerException failed) {
            throw new IllegalArgumentException("Invalid Lumi update manifest", failed);
        }
    }

    private static Optional<Candidate> candidate(
            JsonObject value, String minecraftVersion) {
        require("fabric".equals(text(value, "loader", 16)), "Wrong update loader");
        String version = text(value, "version", 32);
        String summary = text(value, "summary", 4096);
        var supported = value.getAsJsonArray("minecraftVersions");
        require(supported != null && supported.size() <= 32,
                "Invalid Minecraft version list");
        boolean compatible = supported.asList().stream()
                .map(element -> element.getAsString())
                .peek(item -> require(item.length() <= 32, "Minecraft version is too long"))
                .anyMatch(minecraftVersion::equals);
        URI download = trustedReleaseUri(text(value, "downloadUrl", 512));
        URI changelog = value.has("changelogUrl")
                ? trustedReleaseUri(text(value, "changelogUrl", 512))
                : download;
        UpdateRelease release = new UpdateRelease(
                version, minecraftVersion, summary, download, changelog);
        return compatible
                ? Optional.of(new Candidate(parseVersion(version), release))
                : Optional.empty();
    }

    private static URI trustedReleaseUri(String value) {
        URI uri = URI.create(value);
        require("https".equals(uri.getScheme())
                        && "github.com".equalsIgnoreCase(uri.getHost()),
                "Untrusted update release host");
        return uri;
    }

    private static String text(JsonObject object, String name, int maxLength) {
        String value = object.get(name).getAsString();
        require(!value.isBlank() && value.length() <= maxLength,
                "Invalid update field: " + name);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private record Candidate(SemanticVersion number, UpdateRelease release) { }

    private static SemanticVersion parseVersion(String value) {
        try {
            SemanticVersion parsed = SemanticVersion.parse(value);
            require(parsed.getVersionComponentCount() == 3 && !parsed.hasWildcard(), "Invalid version");
            return parsed;
        } catch (VersionParsingException invalid) {
            throw new IllegalArgumentException("Invalid version", invalid);
        }
    }
}
