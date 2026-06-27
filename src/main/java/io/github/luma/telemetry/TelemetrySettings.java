package io.github.luma.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

public record TelemetrySettings(
        int schemaVersion,
        boolean enabled,
        int noticeSeenVersion,
        String endpointUrl,
        String installationId,
        Instant rotatedAt,
        boolean installationReported
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int CURRENT_NOTICE_VERSION = 1;
    private static final Duration INSTALLATION_ID_ROTATION = Duration.ofDays(30);

    public static TelemetrySettings defaults(String endpointUrl, Supplier<String> installationIds) {
        return defaults(endpointUrl, true, installationIds);
    }

    public static TelemetrySettings defaults(String endpointUrl, boolean enabled, Supplier<String> installationIds) {
        return new TelemetrySettings(
                CURRENT_SCHEMA_VERSION,
                enabled,
                0,
                endpointUrl,
                nextInstallationId(installationIds),
                Instant.now(),
                false
        );
    }

    public TelemetrySettings normalized(String defaultEndpointUrl, Supplier<String> installationIds) {
        return new TelemetrySettings(
                CURRENT_SCHEMA_VERSION,
                this.enabled,
                Math.max(0, this.noticeSeenVersion),
                blank(this.endpointUrl) ? defaultEndpointUrl : this.endpointUrl,
                blank(this.installationId) ? nextInstallationId(installationIds) : this.installationId,
                this.rotatedAt == null ? Instant.now() : this.rotatedAt,
                this.installationReported
        );
    }

    public TelemetrySettings rotateIfExpired(Instant now, Supplier<String> installationIds) {
        Instant effectiveNow = now == null ? Instant.now() : now;
        Instant effectiveRotatedAt = this.rotatedAt == null ? effectiveNow : this.rotatedAt;
        if (Duration.between(effectiveRotatedAt, effectiveNow).compareTo(INSTALLATION_ID_ROTATION) < 0) {
            return this;
        }
        return new TelemetrySettings(
                this.schemaVersion,
                this.enabled,
                this.noticeSeenVersion,
                this.endpointUrl,
                nextInstallationId(installationIds),
                effectiveNow,
                this.installationReported
        );
    }

    public TelemetrySettings withEnabled(boolean enabled) {
        return new TelemetrySettings(
                this.schemaVersion,
                enabled,
                this.noticeSeenVersion,
                this.endpointUrl,
                this.installationId,
                this.rotatedAt,
                this.installationReported
        );
    }

    public TelemetrySettings withNoticeSeen() {
        return new TelemetrySettings(
                this.schemaVersion,
                this.enabled,
                CURRENT_NOTICE_VERSION,
                this.endpointUrl,
                this.installationId,
                this.rotatedAt,
                this.installationReported
        );
    }

    public TelemetrySettings withInstallationReported() {
        return new TelemetrySettings(
                this.schemaVersion,
                this.enabled,
                this.noticeSeenVersion,
                this.endpointUrl,
                this.installationId,
                this.rotatedAt,
                true
        );
    }

    public boolean noticeSeen() {
        return this.noticeSeenVersion >= CURRENT_NOTICE_VERSION;
    }

    private static String nextInstallationId(Supplier<String> installationIds) {
        return Objects.requireNonNullElseGet(installationIds == null ? null : installationIds.get(), () ->
                java.util.UUID.randomUUID().toString());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
