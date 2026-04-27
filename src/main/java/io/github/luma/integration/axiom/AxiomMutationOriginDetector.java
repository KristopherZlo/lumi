package io.github.luma.integration.axiom;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;

public final class AxiomMutationOriginDetector {

    private static final String AXIOM_MOD_ID = "axiom";
    private static final String AXIOM_CLIENT_API_MOD_ID = "axiomclientapi";
    private static final Duration OPERATION_IDLE_TIMEOUT = Duration.ofMillis(750);
    private static final int STACK_TRACE_LIMIT = 96;
    private static final AxiomMutationOriginDetector INSTANCE = new AxiomMutationOriginDetector(
            AxiomMutationOriginDetector::defaultAxiomAvailable,
            AxiomMutationOriginDetector::currentStackClassNames,
            System::nanoTime
    );

    private final BooleanSupplier axiomAvailable;
    private final Supplier<List<String>> stackClassNames;
    private final LongSupplier nanoTime;
    private final long operationIdleTimeoutNanos;
    private final ThreadLocal<ObservedOperation> currentOperation = new ThreadLocal<>();
    private volatile Boolean cachedAxiomAvailable;

    public static AxiomMutationOriginDetector getInstance() {
        return INSTANCE;
    }

    AxiomMutationOriginDetector(
            BooleanSupplier axiomAvailable,
            Supplier<List<String>> stackClassNames,
            LongSupplier nanoTime
    ) {
        this.axiomAvailable = axiomAvailable;
        this.stackClassNames = stackClassNames;
        this.nanoTime = nanoTime;
        this.operationIdleTimeoutNanos = OPERATION_IDLE_TIMEOUT.toNanos();
    }

    public Optional<ObservedAxiomOperation> detectOperation() {
        if (!this.isAxiomAvailable()) {
            return Optional.empty();
        }

        List<String> classNames = this.stackClassNames.get();
        if (classNames == null || classNames.isEmpty()) {
            return Optional.empty();
        }

        boolean axiomFramePresent = classNames.stream()
                .filter(AxiomMutationOriginDetector::isExternalAxiomClassName)
                .findFirst()
                .isPresent();
        if (!axiomFramePresent) {
            return Optional.empty();
        }

        return Optional.of(this.operationFor());
    }

    public boolean canDetectOperation() {
        return this.isAxiomAvailable();
    }

    private ObservedAxiomOperation operationFor() {
        long now = this.nanoTime.getAsLong();
        ObservedOperation operation = this.currentOperation.get();
        if (operation == null || operation.expired(now, this.operationIdleTimeoutNanos)) {
            operation = new ObservedOperation("axiom", "axiom-" + UUID.randomUUID(), now);
            this.currentOperation.set(operation);
        } else {
            operation.touch(now);
        }
        return operation.toObservedOperation();
    }

    private boolean isAxiomAvailable() {
        Boolean cached = this.cachedAxiomAvailable;
        if (cached != null) {
            return cached;
        }

        boolean available = this.axiomAvailable.getAsBoolean();
        this.cachedAxiomAvailable = available;
        return available;
    }

    static boolean isExternalAxiomClassName(String className) {
        if (className == null || className.isBlank()) {
            return false;
        }

        String normalized = className.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("io.github.luma.")) {
            return false;
        }

        return normalized.startsWith("axiom.")
                || normalized.contains(".axiom.")
                || normalized.contains(".axiomclientapi.")
                || normalized.startsWith("com.moulberry.axiom")
                || normalized.contains(".moulberry.axiom");
    }

    private static boolean defaultAxiomAvailable() {
        FabricLoader loader = FabricLoader.getInstance();
        return loader.isModLoaded(AXIOM_MOD_ID)
                || loader.isModLoaded(AXIOM_CLIENT_API_MOD_ID)
                || classExists("com.moulberry.axiomclientapi.AxiomClientAPI")
                || classExists("com.moulberry.axiomclientapi.service.ToolRegistryService")
                || classExists("com.moulberry.axiomclientapi.CustomTool");
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, AxiomMutationOriginDetector.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    private static List<String> currentStackClassNames() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        List<String> classNames = new ArrayList<>(Math.min(stackTrace.length, STACK_TRACE_LIMIT));
        for (int i = 0; i < stackTrace.length && i < STACK_TRACE_LIMIT; i++) {
            classNames.add(stackTrace[i].getClassName());
        }
        return classNames;
    }

    private static final class ObservedOperation {

        private final String actor;
        private final String actionId;
        private long lastSeenNanos;

        private ObservedOperation(String actor, String actionId, long lastSeenNanos) {
            this.actor = actor;
            this.actionId = actionId;
            this.lastSeenNanos = lastSeenNanos;
        }

        private boolean expired(long now, long idleTimeoutNanos) {
            return now - this.lastSeenNanos > idleTimeoutNanos;
        }

        private void touch(long now) {
            this.lastSeenNanos = now;
        }

        private ObservedAxiomOperation toObservedOperation() {
            return new ObservedAxiomOperation(this.actor, this.actionId);
        }
    }
}
