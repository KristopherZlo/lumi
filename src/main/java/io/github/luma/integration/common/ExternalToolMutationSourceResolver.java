package io.github.luma.integration.common;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves known external builder-tool stack frames at Minecraft mutation
 * boundaries without letting broad player contexts hide tool-assisted edits.
 */
public final class ExternalToolMutationSourceResolver {

    private static final ExternalToolMutationSourceResolver INSTANCE = new ExternalToolMutationSourceResolver(
            ExternalToolMutationOriginDetector.getInstance()
    );

    private final ExternalToolMutationDetector detector;

    public static ExternalToolMutationSourceResolver getInstance() {
        return INSTANCE;
    }

    ExternalToolMutationSourceResolver(ExternalToolMutationDetector detector) {
        this.detector = Objects.requireNonNull(detector, "detector");
    }

    public Optional<ObservedExternalToolOperation> detectUnattributedOperation(boolean captureSuppressed) {
        if (captureSuppressed) {
            return Optional.empty();
        }
        return this.detector.detectOperation();
    }

    public Optional<ObservedExternalToolOperation> detectPlayerSourceOverride(
            WorldMutationSource currentSource,
            boolean captureSuppressed
    ) {
        if (captureSuppressed || currentSource != WorldMutationSource.PLAYER) {
            return Optional.empty();
        }
        return this.detector.detectOperation()
                .filter(this::canOverridePlayerSource);
    }

    private boolean canOverridePlayerSource(ObservedExternalToolOperation operation) {
        return operation != null && operation.source() == WorldMutationSource.AXIOM;
    }
}
