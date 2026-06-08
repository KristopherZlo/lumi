package io.github.luma.telemetry;

import java.net.URI;
import java.util.Locale;

final class TelemetryEndpointPolicy {

    boolean sendable(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(endpointUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return !normalizedHost.equals("example") && !normalizedHost.endsWith(".example");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
