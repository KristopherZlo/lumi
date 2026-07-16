package io.github.lumi.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpdateCheckerTest {
    @Test
    void fallsBackToSecondFixedSource() {
        URI primary = URI.create("https://primary.invalid/manifest.json");
        URI fallback = URI.create("https://fallback.invalid/manifest.json");
        var requested = new ArrayList<URI>();
        UpdateChecker checker = new UpdateChecker(
                List.of(primary, fallback),
                source -> {
                    requested.add(source);
                    if (source.equals(primary)) {
                        throw new IllegalStateException("offline");
                    }
                    return manifest();
                },
                new UpdateManifestParser(), "0.1.0", "1.21.11");

        UpdateCheckResult result = checker.check();

        assertEquals(List.of(primary, fallback), requested);
        assertEquals(UpdateCheckResult.Status.UPDATE_AVAILABLE, result.status());
    }

    @Test
    void reportsFailureWhenEverySourceFails() {
        UpdateChecker checker = new UpdateChecker(
                List.of(URI.create("https://one.invalid"), URI.create("https://two.invalid")),
                source -> {
                    throw new IllegalStateException("offline");
                },
                new UpdateManifestParser(), "0.1.0", "1.21.11");

        assertEquals(UpdateCheckResult.Status.FAILED, checker.check().status());
    }

    private static byte[] manifest() {
        return """
                {"schema":1,"modId":"lumi","versions":[{
                  "version":"0.2.0","minecraftVersions":["1.21.11"],
                  "loader":"fabric","summary":"Update",
                  "downloadUrl":"https://github.com/KristopherZlo/lumi/releases/tag/v0.2.0"
                }]}
                """.getBytes(StandardCharsets.UTF_8);
    }
}
