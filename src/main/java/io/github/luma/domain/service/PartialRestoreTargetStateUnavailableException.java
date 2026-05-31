package io.github.luma.domain.service;

/**
 * Signals that full target-state reconstruction cannot safely cover the
 * requested partial-restore scope.
 */
final class PartialRestoreTargetStateUnavailableException extends IllegalArgumentException {

    PartialRestoreTargetStateUnavailableException(String message) {
        super(message);
    }
}
