package io.github.lumi.minecraft.operation;

import java.util.Objects;
import java.util.OptionalDouble;

/** Immutable phase and optional bounded work count safe to publish to clients. */
public record OperationProgress(String phase, long completed, long total) {
    public OperationProgress {
        Objects.requireNonNull(phase, "phase");
        if (phase.isBlank() || phase.length() > 128
                || phase.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid operation progress phase");
        }
        if (total < 0 || completed < 0 || total == 0 && completed != 0
                || total > 0 && completed > total) {
            throw new IllegalArgumentException("Invalid operation progress count");
        }
    }

    public static OperationProgress indeterminate(String phase) {
        return new OperationProgress(phase, 0, 0);
    }

    public OptionalDouble fraction() {
        return total == 0 ? OptionalDouble.empty()
                : OptionalDouble.of((double) completed / total);
    }
}
