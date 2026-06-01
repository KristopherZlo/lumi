package io.github.luma.client.update;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCandidateSelectorTest {

    private final UpdateCandidateSelector selector = new UpdateCandidateSelector();

    @Test
    void selectsNewerReleaseForCurrentMinecraftOnly() {
        UpdateManifest manifest = new UpdateManifest(1, "lumi", List.of(
                release("0.1.0-alpha.2", 100002, List.of("1.21.10")),
                release("0.1.0-alpha.2", 100002, List.of("1.21.11")),
                release("0.2.0", 200000, List.of("1.21.12"))
        ));

        UpdateCheckResult result = this.selector.select(
                manifest,
                new InstalledModInfo("0.1.0-alpha.1", "1.21.11", "fabric")
        );

        assertTrue(result.available());
        assertEquals("0.1.0-alpha.2", result.release().version());
        assertEquals(List.of("1.21.11"), result.release().minecraftVersions());
    }

    @Test
    void ignoresSameVersionAndDifferentLoader() {
        UpdateManifest manifest = new UpdateManifest(1, "lumi", List.of(
                release("0.1.0-alpha.1", 100001, List.of("1.21.11")),
                release("0.1.0-alpha.2", 100002, List.of("1.21.11"), "forge", "stable")
        ));

        UpdateCheckResult result = this.selector.select(
                manifest,
                new InstalledModInfo("0.1.0-alpha.1", "1.21.11", "fabric")
        );

        assertTrue(result.upToDate());
    }

    @Test
    void stableReleaseBeatsPrereleaseForSameBaseVersion() {
        UpdateManifest manifest = new UpdateManifest(1, "lumi", List.of(
                release("0.1.0-alpha.2", 100002, List.of("1.21.11")),
                release("0.1.0", 100100, List.of("1.21.11"))
        ));

        UpdateCheckResult result = this.selector.select(
                manifest,
                new InstalledModInfo("0.1.0-alpha.1", "1.21.11", "fabric")
        );

        assertTrue(result.available());
        assertEquals("0.1.0", result.release().version());
    }

    @Test
    void selectsAlphaChannelReleaseForAlphaLine() {
        UpdateManifest manifest = new UpdateManifest(1, "lumi", List.of(
                release("0.1.0-alpha.2", 100002, List.of("1.21.11"), "fabric", "alpha")
        ));

        UpdateCheckResult result = this.selector.select(
                manifest,
                new InstalledModInfo("0.1.0-alpha", "1.21.11", "fabric")
        );

        assertTrue(result.available());
        assertEquals("0.1.0-alpha.2", result.release().version());
    }

    private static UpdateRelease release(String version, int versionCode, List<String> minecraftVersions) {
        return release(version, versionCode, minecraftVersions, "fabric", "stable");
    }

    private static UpdateRelease release(
            String version,
            int versionCode,
            List<String> minecraftVersions,
            String loader,
            String channel
    ) {
        return new UpdateRelease(
                version,
                versionCode,
                minecraftVersions,
                loader,
                channel,
                "Lumi " + version,
                "Summary",
                "https://example.com/download",
                "https://example.com/changelog",
                ""
        );
    }
}
