package io.github.luma.client.update;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdateSourceChainTest {

    @Test
    void fallsBackToGithubWhenPrimarySourceFails() throws Exception {
        UpdateSource primary = new FakeSource("site", null, new IOException("offline"));
        UpdateSource github = new FakeSource("github", manifest("0.1.0-alpha.2"), null);

        SourcedUpdateManifest loaded = new UpdateSourceChain(List.of(primary, github)).load();

        assertEquals("github", loaded.sourceName());
        assertEquals("0.1.0-alpha.2", loaded.manifest().versions().getFirst().version());
    }

    private static UpdateManifest manifest(String version) {
        return new UpdateManifest(1, "lumi", List.of(new UpdateRelease(
                version,
                100002,
                List.of("1.21.11"),
                "fabric",
                "stable",
                "Lumi " + version,
                "Summary",
                "https://example.com/download",
                "https://example.com/changelog",
                ""
        )));
    }

    private record FakeSource(String name, UpdateManifest manifest, Exception exception) implements UpdateSource {

        @Override
        public SourcedUpdateManifest load() throws Exception {
            if (this.exception != null) {
                throw this.exception;
            }
            return new SourcedUpdateManifest(this.name, this.manifest);
        }
    }
}
