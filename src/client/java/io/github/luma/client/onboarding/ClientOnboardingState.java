package io.github.luma.client.onboarding;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record ClientOnboardingState(
        int schemaVersion,
        int completedOnboardingVersion,
        Set<String> dismissedContextualHintIds
) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public ClientOnboardingState {
        dismissedContextualHintIds = sanitizedHints(dismissedContextualHintIds);
    }

    public ClientOnboardingState(int schemaVersion, int completedOnboardingVersion) {
        this(schemaVersion, completedOnboardingVersion, Set.of());
    }

    public static ClientOnboardingState empty() {
        return new ClientOnboardingState(CURRENT_SCHEMA_VERSION, 0, Set.of());
    }

    public ClientOnboardingState normalized() {
        int completed = Math.max(0, this.completedOnboardingVersion);
        return new ClientOnboardingState(CURRENT_SCHEMA_VERSION, completed, this.dismissedContextualHintIds);
    }

    public ClientOnboardingState withCompletedVersion(int version) {
        return new ClientOnboardingState(CURRENT_SCHEMA_VERSION, Math.max(0, version), this.dismissedContextualHintIds);
    }

    public ClientOnboardingState withDismissedContextualHint(String hintId) {
        LinkedHashSet<String> hints = new LinkedHashSet<>(this.dismissedContextualHintIds);
        if (hintId != null && !hintId.isBlank()) {
            hints.add(hintId.trim());
        }
        return new ClientOnboardingState(CURRENT_SCHEMA_VERSION, this.completedOnboardingVersion, hints);
    }

    public ClientOnboardingState withClearedContextualHints() {
        return new ClientOnboardingState(CURRENT_SCHEMA_VERSION, this.completedOnboardingVersion, Set.of());
    }

    private static Set<String> sanitizedHints(Set<String> hints) {
        if (hints == null || hints.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        for (String hint : hints) {
            if (hint != null && !hint.isBlank()) {
                sanitized.add(hint.trim());
            }
        }
        return Collections.unmodifiableSet(sanitized);
    }
}
