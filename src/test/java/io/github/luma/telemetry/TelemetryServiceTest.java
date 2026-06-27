package io.github.luma.telemetry;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void disabledTelemetryDropsEventsAndClearsSpool() {
        TelemetrySpoolRepository spool = new TelemetrySpoolRepository(this.tempDir.resolve("telemetry-spool.json"), 10);
        spool.save(List.of(event("queued")));
        TelemetryService service = TelemetryService.testing(
                new TelemetrySettings(1, false, 0, "https://telemetry.example.test/v1/events/batch", "install-a", Instant.parse("2026-01-01T00:00:00Z"), false),
                spool,
                new TelemetryEnvironmentProvider.Static(new TelemetryEnvironment("lumi", "mc", "loader", "java", "os", "arch", List.of())),
                Runnable::run,
                (endpoint, events) -> TelemetrySendResult.success(events.size())
        );

        service.recordOperationRejected("save", "luma.status.operation_failed", new IllegalStateException("C:\\Users\\Alex\\world"));
        service.flushNow();

        assertTrue(spool.load().isEmpty());
    }

    @Test
    void defaultSingletonFallsBackToNoOpWhenFabricConfigIsUnavailable() {
        TelemetryService service = TelemetryService.getInstance();

        assertFalse(service.settings().enabled());
        assertEquals(0, service.pendingEventCount());
    }

    @Test
    void enabledTelemetryQueuesAllowlistedRejectedAction() {
        TelemetrySpoolRepository spool = new TelemetrySpoolRepository(this.tempDir.resolve("telemetry-spool.json"), 10);
        TelemetryService service = TelemetryService.testing(
                TelemetrySettings.defaults("https://telemetry.lumimod.dev/v1/events/batch", () -> "install-a"),
                spool,
                new TelemetryEnvironmentProvider.Static(new TelemetryEnvironment("lumi", "mc", "loader", "java", "os", "arch", List.of())),
                Runnable::run,
                (endpoint, events) -> TelemetrySendResult.failure("offline")
        );

        service.recordOperationRejected(
                "save",
                "luma.status.operation_failed",
                new IllegalStateException("Project metadata is missing for Castle World")
        );
        service.flushNow();

        List<TelemetryEvent> events = spool.load();
        assertEquals(1, events.size());
        assertEquals(TelemetryEventType.OPERATION_REJECTED, events.getFirst().type());
        Map<String, String> payload = events.getFirst().payload();
        assertEquals("save", payload.get("action"));
        assertEquals("luma.status.operation_failed", payload.get("statusKey"));
        assertEquals("java.lang.IllegalStateException", payload.get("failureClass"));
        assertFalse(payload.containsKey("failure"));
        assertFalse(payloadText(events.getFirst()).contains("Castle World"));
    }

    @Test
    void operationFailurePayloadOmitsSnapshotDetailsAndThrowableMessages() {
        TelemetrySpoolRepository spool = new TelemetrySpoolRepository(this.tempDir.resolve("telemetry-spool.json"), 10);
        TelemetryService service = TelemetryService.testing(
                TelemetrySettings.defaults("https://telemetry.lumimod.dev/v1/events/batch", () -> "install-a"),
                spool,
                new TelemetryEnvironmentProvider.Static(new TelemetryEnvironment("lumi", "mc", "loader", "java", "os", "arch", List.of())),
                Runnable::run,
                (endpoint, events) -> TelemetrySendResult.failure("offline")
        );
        OperationHandle handle = new OperationHandle(
                "patch-abc123",
                "project-castle-world",
                "restore-version",
                Instant.parse("2026-06-08T08:00:00Z"),
                false
        );
        OperationSnapshot snapshot = new OperationSnapshot(
                handle,
                OperationStage.APPLYING,
                new OperationProgress(12, 42, "chunks"),
                "Applying patch abc123 to Castle World at chunk 12:-4 with snapshot snap-456",
                Instant.parse("2026-06-08T08:00:05Z")
        );

        service.recordOperationFailed(
                handle,
                snapshot,
                failureWithPrivateMessage()
        );
        service.flushNow();

        List<TelemetryEvent> events = spool.load();
        assertEquals(1, events.size());
        assertEquals(TelemetryEventType.OPERATION_FAILED, events.getFirst().type());
        Map<String, String> payload = events.getFirst().payload();
        assertEquals("restore-version", payload.get("operation"));
        assertEquals("APPLYING", payload.get("stage"));
        assertEquals("12", payload.get("completedUnits"));
        assertEquals("42", payload.get("totalUnits"));
        assertEquals("chunks", payload.get("unitLabel"));
        assertEquals("java.lang.IllegalStateException", payload.get("failureClass"));
        assertEquals("io.github.luma.minecraft.world.BlockChangeApplier#apply:42", payload.get("failureFrame"));
        assertEquals(
                "io.github.luma.minecraft.world.BlockChangeApplier#apply:42\n"
                        + "io.github.luma.domain.service.RestoreService#restore:77",
                payload.get("failureTrace")
        );
        assertEquals(
                "java.lang.IllegalStateException -> java.lang.IllegalArgumentException",
                payload.get("failureCauseChain")
        );
        assertFalse(payload.containsKey("detail"));
        assertFalse(payload.containsKey("failure"));

        String payloadText = payloadText(events.getFirst());
        assertFalse(payloadText.contains("Castle World"));
        assertFalse(payloadText.contains("12:-4"));
        assertFalse(payloadText.contains("abc123"));
        assertFalse(payloadText.contains("snap-456"));
        assertFalse(payloadText.contains("C:\\Users"));
        assertFalse(payloadText.contains("x=12"));
    }

    private static IllegalStateException failureWithPrivateMessage() {
        IllegalArgumentException cause = new IllegalArgumentException("Nested C:\\Users\\Alex\\Castle World failure");
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("io.github.luma.storage.repository.SnapshotRepository", "load", "SnapshotRepository.java", 31)
        });
        IllegalStateException failure = new IllegalStateException(
                "Failed block write at x=12 y=64 z=-4 in C:\\Users\\Alex\\Castle World",
                cause
        );
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("io.github.luma.minecraft.world.BlockChangeApplier", "apply", "BlockChangeApplier.java", 42),
                new StackTraceElement("net.minecraft.world.level.Level", "setBlock", "Level.java", 123),
                new StackTraceElement("io.github.luma.domain.service.RestoreService", "restore", "RestoreService.java", 77)
        });
        return failure;
    }

    @Test
    void defaultEndpointSendsToProductionTelemetryHost() {
        TelemetrySpoolRepository spool = new TelemetrySpoolRepository(this.tempDir.resolve("telemetry-spool.json"), 10);
        CountingSender sender = new CountingSender(TelemetrySendResult.success(1));
        TelemetryService service = TelemetryService.testing(
                TelemetrySettings.defaults(TelemetryService.DEFAULT_ENDPOINT_URL, () -> "install-a"),
                spool,
                new TelemetryEnvironmentProvider.Static(new TelemetryEnvironment("lumi", "mc", "loader", "java", "os", "arch", List.of())),
                Runnable::run,
                sender
        );

        service.recordPerformanceOutlier("restore-version", 100_000L, 10_000L, "APPLYING");
        service.flushNow();

        assertEquals("https://lumi.zloyxp.cc/v1/events/batch", TelemetryService.DEFAULT_ENDPOINT_URL);
        assertEquals(1, sender.calls());
        assertEquals(0, service.pendingEventCount());
        assertTrue(spool.load().isEmpty());
    }

    @Test
    void clientRuntimeReportsInstallationOnce() {
        TelemetrySpoolRepository spool = new TelemetrySpoolRepository(this.tempDir.resolve("telemetry-spool.json"), 10);
        TelemetryService service = TelemetryService.testing(
                TelemetrySettings.defaults("https://telemetry.lumimod.dev/v1/events/batch", () -> "install-a"),
                spool,
                new TelemetryEnvironmentProvider.Static(new TelemetryEnvironment("lumi", "mc", "loader", "java", "os", "arch", List.of())),
                Runnable::run,
                (endpoint, events) -> TelemetrySendResult.failure("offline")
        );

        service.enableClientRuntime();
        service.enableClientRuntime();

        List<TelemetryEvent> events = spool.load();
        assertEquals(1, events.size());
        assertEquals(TelemetryEventType.INSTALLATION_SEEN, events.getFirst().type());
        assertTrue(events.getFirst().payload().isEmpty());
    }

    @Test
    void nonHttpsEndpointDropsEventsWithoutSending() {
        TelemetrySpoolRepository spool = new TelemetrySpoolRepository(this.tempDir.resolve("telemetry-spool.json"), 10);
        CountingSender sender = new CountingSender(TelemetrySendResult.success(1));
        TelemetryService service = TelemetryService.testing(
                TelemetrySettings.defaults("http://telemetry.lumimod.dev/v1/events/batch", () -> "install-a"),
                spool,
                new TelemetryEnvironmentProvider.Static(new TelemetryEnvironment("lumi", "mc", "loader", "java", "os", "arch", List.of())),
                Runnable::run,
                sender
        );

        service.recordPerformanceOutlier("restore-version", 100_000L, 10_000L, "APPLYING");
        service.flushNow();

        assertEquals(0, sender.calls());
        assertEquals(0, service.pendingEventCount());
        assertTrue(spool.load().isEmpty());
    }

    private static TelemetryEvent event(String id) {
        return new TelemetryEvent(
                id,
                1,
                TelemetryEventType.OPERATION_REJECTED,
                Instant.parse("2026-01-01T00:00:00Z"),
                "install-a",
                new TelemetryEnvironment("lumi", "minecraft", "fabric", "java", "os", "arch", List.of()),
                "fingerprint-" + id,
                java.util.Map.of("statusKey", "luma.status.operation_failed")
        );
    }

    private static String payloadText(TelemetryEvent event) {
        return String.join("\n", event.payload().values());
    }

    private static final class CountingSender implements TelemetryBatchSender {

        private final TelemetrySendResult result;
        private int calls;

        private CountingSender(TelemetrySendResult result) {
            this.result = result;
        }

        @Override
        public TelemetrySendResult send(String endpointUrl, List<TelemetryEvent> events) {
            this.calls++;
            return this.result;
        }

        private int calls() {
            return this.calls;
        }
    }
}
