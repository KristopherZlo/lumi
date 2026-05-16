package io.github.luma.client.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdateManifestParserTest {

    @Test
    void parsesWebsiteManifestWithMultipleMinecraftTargets() {
        UpdateManifest manifest = new UpdateManifestParser().parse("""
                {
                  "schema": 1,
                  "modId": "lumi",
                  "versions": [
                    {
                      "version": "0.1.0-alpha.2",
                      "versionCode": 100002,
                      "minecraftVersions": ["1.21.11", "1.21.10"],
                      "loader": "fabric",
                      "channel": "stable",
                      "title": "Lumi 0.1.0 alpha 2",
                      "summary": "Small fix release.",
                      "downloadUrl": "https://example.com/lumi.jar",
                      "changelogUrl": "https://example.com/changelog",
                      "sha256": "abc"
                    }
                  ]
                }
                """);

        assertEquals(1, manifest.schema());
        assertEquals("lumi", manifest.modId());
        assertEquals(1, manifest.versions().size());
        UpdateRelease release = manifest.versions().getFirst();
        assertEquals("0.1.0-alpha.2", release.version());
        assertEquals(100002, release.versionCode());
        assertEquals("1.21.11", release.minecraftVersions().getFirst());
        assertEquals("fabric", release.loader());
        assertEquals("stable", release.channel());
        assertEquals("Small fix release.", release.summary());
    }
}
