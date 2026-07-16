package io.github.lumi.update;

import io.github.lumi.LumiMod;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

/** User-triggered bounded update lookup against Lumi-owned fixed sources. */
public final class UpdateChecker {
    private static final List<URI> SOURCES = List.of(
            URI.create("https://kristopherzlo.github.io/lumi/updates/lumi-fabric.json"),
            URI.create("https://raw.githubusercontent.com/KristopherZlo/lumi/main/"
                    + "updates/lumi-fabric.json"));
    private final List<URI> sources;
    private final Function<URI, byte[]> fetcher;
    private final UpdateManifestParser parser;
    private final String currentVersion;
    private final String minecraftVersion;

    UpdateChecker(
            List<URI> sources,
            Function<URI, byte[]> fetcher,
            UpdateManifestParser parser,
            String currentVersion,
            String minecraftVersion) {
        this.sources = List.copyOf(sources);
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.minecraftVersion = Objects.requireNonNull(minecraftVersion, "minecraftVersion");
    }

    public static UpdateChecker createDefault() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String version = FabricLoader.getInstance().getModContainer(LumiMod.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
        return new UpdateChecker(
                SOURCES,
                source -> fetch(client, source, version),
                new UpdateManifestParser(),
                version,
                SharedConstants.getCurrentVersion().name());
    }

    public UpdateCheckResult check() {
        for (URI source : sources) {
            try {
                return parser.parse(fetcher.apply(source), currentVersion, minecraftVersion);
            } catch (RuntimeException failed) {
                LumiMod.LOGGER.debug("Lumi update source failed: {}", source, failed);
            }
        }
        return UpdateCheckResult.failed();
    }

    private static byte[] fetch(HttpClient client, URI source, String version) {
        HttpRequest request = HttpRequest.newBuilder(source)
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("User-Agent", "Lumi/" + version)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) {
                    throw new IllegalStateException(
                            "Update source returned HTTP " + response.statusCode());
                }
            }
            try (InputStream input = response.body()) {
                byte[] content = input.readNBytes(UpdateManifestParser.MAX_BYTES + 1);
                if (content.length > UpdateManifestParser.MAX_BYTES) {
                    throw new IllegalArgumentException("Update manifest is too large");
                }
                return content;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Update check interrupted", interrupted);
        } catch (IOException failed) {
            throw new IllegalStateException("Update check failed", failed);
        }
    }
}
