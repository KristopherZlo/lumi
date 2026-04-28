package io.github.luma.integration.common;

import java.util.Optional;

/**
 * Detects whether the current mutation stack belongs to a supported external
 * builder tool.
 */
@FunctionalInterface
public interface ExternalToolMutationDetector {

    Optional<ObservedExternalToolOperation> detectOperation();
}
