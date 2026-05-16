package io.github.luma.client.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpUpdateSource implements UpdateSource {

    private final String name;
    private final URI uri;
    private final Duration timeout;
    private final HttpClient client;
    private final UpdateManifestParser parser;

    public HttpUpdateSource(String name, String uri, Duration timeout) {
        this(name, URI.create(uri), timeout, HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), new UpdateManifestParser());
    }

    HttpUpdateSource(
            String name,
            URI uri,
            Duration timeout,
            HttpClient client,
            UpdateManifestParser parser
    ) {
        this.name = name == null ? "" : name.trim();
        this.uri = uri;
        this.timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
        this.client = client;
        this.parser = parser;
    }

    @Override
    public SourcedUpdateManifest load() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(this.uri)
                .GET()
                .timeout(this.timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "LumiUpdateChecker")
                .build();
        HttpResponse<String> response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Update manifest " + this.name + " returned HTTP " + response.statusCode());
        }
        return new SourcedUpdateManifest(this.name, this.parser.parse(response.body()));
    }
}
