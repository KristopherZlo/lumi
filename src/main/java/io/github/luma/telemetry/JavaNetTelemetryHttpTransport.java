package io.github.luma.telemetry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class JavaNetTelemetryHttpTransport implements TelemetryHttpTransport {

    private final HttpClient client;

    JavaNetTelemetryHttpTransport(Duration connectTimeout) {
        this(HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    JavaNetTelemetryHttpTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public TelemetryHttpResponse postJson(String endpointUrl, String body, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpointUrl))
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                .timeout(timeout == null ? Duration.ofSeconds(3) : timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "LumiDiagnosticTelemetry")
                .build();
        HttpResponse<String> response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
        return new TelemetryHttpResponse(response.statusCode(), response.body());
    }
}
