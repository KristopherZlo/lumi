package io.github.lumi.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UpdateManifestParserTest {
    private final UpdateManifestParser parser = new UpdateManifestParser();

    @Test
    void selectsNewestCompatibleRelease() {
        UpdateCheckResult result = parser.parse(manifest("""
                {"version":"0.2.0","minecraftVersions":["1.21.11"],
                 "loader":"fabric","summary":"Fast restore",
                 "downloadUrl":"https://github.com/KristopherZlo/lumi/releases/tag/v0.2.0",
                 "changelogUrl":"https://github.com/KristopherZlo/lumi/releases/tag/v0.2.0-notes"}
                """), "0.2.0-dev", "1.21.11");

        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
        assertEquals("0.2.0", result.release().orElseThrow().version());
        assertEquals("Fast restore", result.release().orElseThrow().summary());
        assertEquals("https://github.com/KristopherZlo/lumi/releases/tag/v0.2.0-notes",
                result.release().orElseThrow().changelogUri().toString());
    }

    @Test
    void ignoresIncompatibleAndOlderReleases() {
        UpdateCheckResult result = parser.parse(manifest("""
                {"version":"0.1.0","minecraftVersions":["1.21.11"],
                 "loader":"fabric","summary":"Old",
                 "downloadUrl":"https://github.com/KristopherZlo/lumi/releases/tag/v0.1.0"},
                {"version":"9.0.0","minecraftVersions":["1.22"],
                 "loader":"fabric","summary":"Other Minecraft",
                 "downloadUrl":"https://github.com/KristopherZlo/lumi/releases/tag/v9.0.0"}
                """), "0.2.0-dev", "1.21.11");

        assertEquals(UpdateCheckResult.Status.UP_TO_DATE, result.status());
    }

    @Test
    void rejectsUntrustedDownloadHost() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(manifest("""
                {"version":"0.2.0","minecraftVersions":["1.21.11"],
                 "loader":"fabric","summary":"Bad link",
                 "downloadUrl":"https://example.com/lumi.jar"}
                """), "0.1.0", "1.21.11"));
    }

    @Test
    void fallsBackToReleasePageWhenChangelogIsAbsent() {
        UpdateCheckResult result = parser.parse(manifest("""
                {"version":"0.2.0","minecraftVersions":["1.21.11"],
                 "loader":"fabric","summary":"Notes",
                 "downloadUrl":"https://github.com/KristopherZlo/lumi/releases/tag/v0.2.0"}
                """), "0.1.0", "1.21.11");

        assertEquals(result.release().orElseThrow().downloadUri(),
                result.release().orElseThrow().changelogUri());
    }

    @Test
    void ordersPrereleaseNumbersAndIgnoresBuildMetadata() {
        byte[] releases = manifest(
                release("0.3.0-rc.2", "[\"26.1.2\"]", "old") + ","
                        + release("0.3.0-rc.10+mc26.1", "[\"26.1.2\"]", "latest"));
        var result = parser.parse(releases, "0.3.0-rc.9", "26.1.2");
        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
        assertEquals("0.3.0-rc.10+mc26.1", result.release().orElseThrow().version());
        assertEquals(UpdateCheckResult.Status.UP_TO_DATE,
                parser.parse(releases, "0.3.0-rc.10+another-build", "26.1.2").status());
    }

    @Test
    void routesGroupedUpdatesAndLeavesRetiredGamesOnTheirLastRelease() {
        byte[] releases = manifest(
                release("0.3.0", "[\"1.21.11\"]", "legacy") + ","
                        + release("0.3.0", "[\"26.1.1\",\"26.1.2\"]", "family") + ","
                        + release("0.3.0", "[\"26.2\"]", "262") + ","
                        + release("0.3.0", "[\"26.3\"]", "263"));
        for (var entry : java.util.Map.of("1.21.11", "legacy", "26.1.1", "family",
                "26.1.2", "family", "26.2", "262", "26.3", "263").entrySet()) {
            var result = parser.parse(releases, "0.2.0", entry.getKey());
            assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
            assertEquals("https://github.com/KristopherZlo/lumi/releases/download/v0.3.0/"
                    + entry.getValue() + ".jar", result.release().orElseThrow().downloadUri().toString());
        }
        assertEquals(UpdateCheckResult.Status.UP_TO_DATE, parser.parse(releases, "0.2.0", "26.1").status());
    }

    private static String release(String version, String games, String artifact) {
        return """
                {"version":"%s","minecraftVersions":%s,"loader":"fabric","summary":"Update",
                 "downloadUrl":"https://github.com/KristopherZlo/lumi/releases/download/v0.3.0/%s.jar"}
                """.formatted(version, games, artifact);
    }

    private static byte[] manifest(String releases) {
        return ("""
                {"schema":1,"modId":"lumi","versions":[%s]}
                """.formatted(releases)).getBytes(StandardCharsets.UTF_8);
    }
}
