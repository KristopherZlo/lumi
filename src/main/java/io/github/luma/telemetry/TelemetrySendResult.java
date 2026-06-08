package io.github.luma.telemetry;

public record TelemetrySendResult(
        boolean success,
        int acceptedEvents,
        String detail
) {

    public static TelemetrySendResult success(int acceptedEvents) {
        return new TelemetrySendResult(true, Math.max(0, acceptedEvents), "");
    }

    public static TelemetrySendResult failure(String detail) {
        return new TelemetrySendResult(false, 0, detail == null ? "" : detail);
    }
}
