package io.github.lumi.telemetry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.lumi.LumiMod;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** Fixed-destination HTTPS transport for already-sanitized events. */
final class TelemetryHttpSender {
    static final URI ENDPOINT = URI.create("https://lumi.zloyxp.cc/v1/events/batch");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    boolean send(List<TelemetryEvent> events) {
        if (events.isEmpty()) {
            return true;
        }
        try {
            String body = body(events);
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Lumi/2")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            int status = client.send(
                    request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300;
        } catch (Exception failed) {
            LumiMod.LOGGER.debug("Lumi telemetry send failed", failed);
            return false;
        }
    }

    static String body(List<TelemetryEvent> events) {
        return GSON.toJson(new Batch(1, events));
    }

    private record Batch(int schemaVersion, List<TelemetryEvent> events) { }
}
