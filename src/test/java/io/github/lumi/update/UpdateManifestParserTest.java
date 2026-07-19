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

    private static byte[] manifest(String releases) {
        return ("""
                {"schema":1,"modId":"lumi","versions":[%s]}
                """.formatted(releases)).getBytes(StandardCharsets.UTF_8);
    }
}
