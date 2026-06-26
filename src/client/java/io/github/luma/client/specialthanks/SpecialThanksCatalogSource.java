package io.github.luma.client.specialthanks;

import io.github.luma.LumaMod;
import io.github.luma.storage.GsonProvider;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public final class SpecialThanksCatalogSource {

    private static final String RESOURCE_PATH = "assets/lumi/special-thanks.json";
    private static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/KristopherZlo/lumi/main/src/main/resources/assets/lumi/special-thanks.json";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final List<SpecialThanksEntry> FALLBACK = List.of(
            new SpecialThanksEntry("ImZlo", "ImZlo", "Creator and maintainer")
    );

    private final HttpClient client;
    private final URI uri;

    public SpecialThanksCatalogSource() {
        this(HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), URI.create(DEFAULT_URL));
    }

    SpecialThanksCatalogSource(HttpClient client, URI uri) {
        this.client = client;
        this.uri = uri;
    }

    public List<SpecialThanksEntry> loadBundled() {
        try (InputStream stream = SpecialThanksCatalogSource.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                return FALLBACK;
            }
            return this.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception exception) {
            LumaMod.LOGGER.debug("Failed to load bundled Lumi special thanks catalog", exception);
            return FALLBACK;
        }
    }

    public List<SpecialThanksEntry> loadRemoteOrBundled() {
        try {
            HttpRequest request = HttpRequest.newBuilder(this.uri)
                    .GET()
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "LumiSpecialThanks")
                    .build();
            HttpResponse<String> response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return this.parse(response.body());
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.debug("Failed to load remote Lumi special thanks catalog", exception);
        }
        return this.loadBundled();
    }

    private List<SpecialThanksEntry> parse(String json) {
        SpecialThanksCatalog catalog = GsonProvider.gson().fromJson(json, SpecialThanksCatalog.class);
        if (catalog == null || catalog.people().isEmpty()) {
            return FALLBACK;
        }
        return catalog.people();
    }
}
