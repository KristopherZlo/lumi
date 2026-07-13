package io.github.luma.integration.common;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class ExternalToolMutationOriginDetector implements ExternalToolMutationDetector {

    private static final Duration OPERATION_IDLE_TIMEOUT = Duration.ofMillis(750);
    private static final int STACK_TRACE_LIMIT = 96;
    private static final List<ToolProfile> TOOL_PROFILES = List.of(
            new ToolProfile(
                    WorldMutationSource.FAWE,
                    "fawe",
                    List.of(
                            "com.fastasyncworldedit.",
                            "com.boydti.fawe."
                    )
            ),
            new ToolProfile(
                    WorldMutationSource.WORLDEDIT,
                    "worldedit",
                    List.of("com.sk89q.worldedit.")
            ),
            new ToolProfile(
                    WorldMutationSource.AXIOM,
                    "axiom",
                    List.of(
                            "com.moulberry.axiom",
                            "com.moulberry.axiomclientapi.",
                            "axiom."
                    )
            ),
            new ToolProfile(
                    WorldMutationSource.EXTERNAL_TOOL,
                    "axion",
                    List.of(
                            "axion.",
                            "com.moulberry.axion.",
                            "dev.moulberry.axion."
                    )
            ),
            new ToolProfile(
                    WorldMutationSource.EXTERNAL_TOOL,
                    "autobuild",
                    List.of(
                            "autobuild.",
                            "net.autobuild.",
                            "com.autobuild."
                    )
            ),
            new ToolProfile(
                    WorldMutationSource.EXTERNAL_TOOL,
                    "simplebuilding",
                    List.of(
                            "simplebuilding.",
                            "net.simplebuilding.",
                            "com.simplebuilding."
                    )
            ),
            new ToolProfile(
                    WorldMutationSource.EXTERNAL_TOOL,
                    "effortlessbuilding",
                    List.of(
                            "nl.requios.effortlessbuilding.",
                            "effortlessbuilding."
                    )
            ),
            new ToolProfile(
                    WorldMutationSource.EXTERNAL_TOOL,
                    "litematica",
                    List.of("fi.dy.masa.litematica.")
            ),
            new ToolProfile(
                    WorldMutationSource.EXTERNAL_TOOL,
                    "tweakeroo",
                    List.of("fi.dy.masa.tweakeroo.")
            )
    );
    private static final ExternalToolMutationOriginDetector INSTANCE = new ExternalToolMutationOriginDetector(
            ExternalToolMutationOriginDetector::currentStackClassNames,
            System::nanoTime,
            new ExternalToolStackTraceDetectionGate(
                    new ExternalToolIntegrationRegistry()::stackTraceDetectionAvailable
            )::available
    );

    private final Supplier<List<String>> stackClassNames;
    private final LongSupplier nanoTime;
    private final BooleanSupplier detectionAvailable;
    private final long operationIdleTimeoutNanos;
    private final ThreadLocal<ObservedOperation> currentOperation = new ThreadLocal<>();

    public static ExternalToolMutationOriginDetector getInstance() {
        return INSTANCE;
    }

    ExternalToolMutationOriginDetector(Supplier<List<String>> stackClassNames, LongSupplier nanoTime) {
        this(stackClassNames, nanoTime, () -> true);
    }

    ExternalToolMutationOriginDetector(
            Supplier<List<String>> stackClassNames,
            LongSupplier nanoTime,
            BooleanSupplier detectionAvailable
    ) {
        this.stackClassNames = Objects.requireNonNull(stackClassNames, "stackClassNames");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.detectionAvailable = Objects.requireNonNull(detectionAvailable, "detectionAvailable");
        this.operationIdleTimeoutNanos = OPERATION_IDLE_TIMEOUT.toNanos();
    }

    public Optional<ObservedExternalToolOperation> detectOperation() {
        if (!this.detectionAvailable()) {
            return Optional.empty();
        }

        List<String> classNames = this.stackClassNames.get();
        if (classNames == null || classNames.isEmpty()) {
            return Optional.empty();
        }

        return this.detectProfile(classNames).map(this::operationFor);
    }

    @Override
    public boolean detectionAvailable() {
        return this.detectionAvailable.getAsBoolean();
    }

    static boolean isExternalToolClassName(String className) {
        return TOOL_PROFILES.stream().anyMatch(profile -> profile.matches(className));
    }

    private Optional<DetectedToolProfile> detectProfile(List<String> classNames) {
        for (ToolProfile profile : TOOL_PROFILES) {
            for (String className : classNames) {
                if (profile.matches(className)) {
                    return Optional.of(new DetectedToolProfile(
                            profile,
                            className.toLowerCase(Locale.ROOT)
                    ));
                }
            }
        }
        return Optional.empty();
    }

    private ObservedExternalToolOperation operationFor(DetectedToolProfile detected) {
        ToolProfile profile = detected.profile();
        long now = this.nanoTime.getAsLong();
        ObservedOperation operation = this.currentOperation.get();
        if (operation == null || !operation.matches(detected) || operation.expired(now, this.operationIdleTimeoutNanos)) {
            operation = new ObservedOperation(profile, detected.stackKey(), profile.actor() + "-" + UUID.randomUUID(), now);
            this.currentOperation.set(operation);
            LumaDebugLog.log(
                    "external-tool-detect",
                    "Detected {} action {}",
                    profile.actor(),
                    operation.actionId
            );
        } else {
            operation.touch(now);
        }
        return operation.toObservedOperation();
    }

    private static List<String> currentStackClassNames() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        List<String> classNames = new ArrayList<>(Math.min(stackTrace.length, STACK_TRACE_LIMIT));
        for (int i = 0; i < stackTrace.length && i < STACK_TRACE_LIMIT; i++) {
            classNames.add(stackTrace[i].getClassName());
        }
        return classNames;
    }

    private record ToolProfile(
            WorldMutationSource source,
            String actor,
            List<String> classNamePrefixes
    ) {

        private boolean matches(String className) {
            if (className == null || className.isBlank()) {
                return false;
            }

            String normalized = className.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("io.github.luma.")) {
                return false;
            }
            for (String prefix : this.classNamePrefixes) {
                if (normalized.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record DetectedToolProfile(ToolProfile profile, String stackKey) {
    }

    private static final class ObservedOperation {

        private final ToolProfile profile;
        private final String stackKey;
        private final String actionId;
        private long lastSeenNanos;

        private ObservedOperation(ToolProfile profile, String stackKey, String actionId, long lastSeenNanos) {
            this.profile = profile;
            this.stackKey = stackKey;
            this.actionId = actionId;
            this.lastSeenNanos = lastSeenNanos;
        }

        private boolean matches(DetectedToolProfile detected) {
            ToolProfile otherProfile = detected.profile();
            return this.profile.source() == otherProfile.source()
                    && this.profile.actor().equals(otherProfile.actor())
                    && Objects.equals(this.stackKey, detected.stackKey());
        }

        private boolean expired(long now, long idleTimeoutNanos) {
            return now - this.lastSeenNanos > idleTimeoutNanos;
        }

        private void touch(long now) {
            this.lastSeenNanos = now;
        }

        private ObservedExternalToolOperation toObservedOperation() {
            return new ObservedExternalToolOperation(this.profile.source(), this.profile.actor(), this.actionId);
        }
    }
}
