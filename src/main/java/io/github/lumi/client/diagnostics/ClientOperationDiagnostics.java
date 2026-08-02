package io.github.lumi.client.diagnostics;

import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.network.RestoreStatisticsPayload;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/** Builds copyable chat diagnostics from the operation events already sent to clients. */
public final class ClientOperationDiagnostics {
    private static final int MAX_ACTIVE = 64;
    private static final int MAX_PHASES = 32;
    private final LinkedHashMap<UUID, Trace> traces = new LinkedHashMap<>();
    private final LongSupplier clock;

    public ClientOperationDiagnostics() {
        this(System::nanoTime);
    }

    ClientOperationDiagnostics(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<Component> accept(
            OperationEventPayload event, boolean enabled) {
        Objects.requireNonNull(event, "event");
        if (!enabled) {
            traces.clear();
            return Optional.empty();
        }
        long now = clock.getAsLong();
        if (event.state() == OperationEventPayload.State.ACCEPTED) {
            trace(event.requestId(), now).transition(
                    event.queuePosition() > 0 ? "queue" : "preparing", now);
            return Optional.empty();
        }
        if (event.state() == OperationEventPayload.State.PROGRESS) {
            trace(event.requestId(), now).transition(
                    event.progress().orElseThrow().phase(), now);
            return Optional.empty();
        }
        Trace trace = traces.remove(event.requestId());
        if (trace != null) {
            trace.finish(now);
        }
        String text = report(event, trace, now);
        return Optional.of(Component.literal(text).append(
                Component.literal(" [copy]").withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.CopyToClipboard(text)))));
    }

    private Trace trace(UUID requestId, long now) {
        Trace trace = traces.computeIfAbsent(
                requestId, ignored -> new Trace(now));
        while (traces.size() > MAX_ACTIVE) {
            traces.remove(traces.keySet().iterator().next());
        }
        return trace;
    }

    private static String report(
            OperationEventPayload event, Trace trace, long now) {
        StringBuilder text = new StringBuilder("[Lumi dev] state=")
                .append(event.state());
        if (trace != null) {
            text.append(" total=").append(duration(now - trace.startedNanos));
            trace.phases.forEach((phase, nanos) -> text.append("\nphase ")
                    .append(phase).append('=').append(duration(nanos)));
        }
        event.restoreStatistics().ifPresent(statistics ->
                appendRestore(text, event.state(), statistics));
        if (isFailure(event.state())) {
            text.append("\nerror=").append(message(event.message()));
        }
        return text.append("\nrequest=").append(event.requestId())
                .append(" dimension=").append(event.dimensionId()).toString();
    }

    private static void appendRestore(
            StringBuilder text,
            OperationEventPayload.State state,
            RestoreStatisticsPayload statistics) {
        text.append("\nrestore accuracy=").append(switch (state) {
            case SUCCEEDED -> "exact (verified)";
            case RETURNED -> "target mismatch; safe return verified";
            case DEGRADED -> "unverified; recovery required";
            default -> "not verified";
        });
        phase(text, "batch-preparation", statistics.batchPreparationNanos());
        phase(text, "chunk-load", statistics.chunkLoadNanos());
        phase(text, "loaded-apply", statistics.loadedApplyNanos());
        phase(text, "storage-read", statistics.storageReadNanos());
        phase(text, "storage-write", statistics.storageWriteNanos());
        phase(text, "storage-barrier", statistics.storageSyncNanos());
        phase(text, "storage-force", statistics.storageForceNanos());
        phase(text, "lighting", statistics.lightingNanos());
        phase(text, "verification", statistics.verificationNanos());
        text.append("\nworld changed-blocks=").append(statistics.changedBlocks())
                .append(" light-sections=").append(statistics.lightSections())
                .append(" loaded-chunks=").append(statistics.loadedChunks())
                .append(" stored-chunks=").append(statistics.storedChunks());
        text.append("\ndurability writes chunk=").append(statistics.chunkWrites())
                .append(" poi=").append(statistics.poiWrites())
                .append(" entity=").append(statistics.entityWrites())
                .append(" regions chunk=").append(statistics.chunkRegions())
                .append(" poi=").append(statistics.poiRegions())
                .append(" entity=").append(statistics.entityRegions());
    }

    private static void phase(StringBuilder text, String name, long nanos) {
        text.append("\nrestore-phase ").append(name)
                .append('=').append(duration(nanos));
    }

    private static String duration(long nanos) {
        if (nanos < TimeUnit.MILLISECONDS.toNanos(1)) {
            return String.format(Locale.ROOT, "%.3f ms", nanos / 1_000_000.0);
        }
        if (nanos < TimeUnit.SECONDS.toNanos(1)) {
            return TimeUnit.NANOSECONDS.toMillis(nanos) + " ms";
        }
        return String.format(Locale.ROOT, "%.3f s", nanos / 1_000_000_000.0);
    }

    private static String message(String value) {
        String rendered = value.startsWith("luma.")
                ? Component.translatable(value).getString() : value;
        return rendered.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    private static boolean isFailure(OperationEventPayload.State state) {
        return state == OperationEventPayload.State.FAILED
                || state == OperationEventPayload.State.RETURNED
                || state == OperationEventPayload.State.DEGRADED;
    }

    private static final class Trace {
        private final long startedNanos;
        private final LinkedHashMap<String, Long> phases = new LinkedHashMap<>();
        private String phase;
        private long phaseStartedNanos;

        private Trace(long now) {
            startedNanos = now;
            phaseStartedNanos = now;
        }

        private void transition(String replacement, long now) {
            String bounded = phases.size() >= MAX_PHASES
                    && !phases.containsKey(replacement) ? "other" : replacement;
            if (bounded.equals(phase)) return;
            finish(now);
            phase = bounded;
            phaseStartedNanos = now;
        }

        private void finish(long now) {
            if (phase != null) {
                phases.merge(phase, Math.max(0, now - phaseStartedNanos), Long::sum);
                phase = null;
            }
        }
    }
}
