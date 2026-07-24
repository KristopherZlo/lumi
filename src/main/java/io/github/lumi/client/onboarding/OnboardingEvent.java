package io.github.lumi.client.onboarding;

import java.util.Objects;
import java.util.UUID;

/** Typed input accepted by the onboarding state machine. */
public sealed interface OnboardingEvent {
    record Navigation(Direction direction) implements OnboardingEvent {
        public Navigation {
            Objects.requireNonNull(direction, "direction");
        }
    }

    record Shortcut(ShortcutKind shortcut, boolean pressed)
            implements OnboardingEvent {
        public Shortcut {
            Objects.requireNonNull(shortcut, "shortcut");
        }
    }

    record WorldCompleted(OnboardingTour.Kind kind) implements OnboardingEvent {
        public WorldCompleted {
            Objects.requireNonNull(kind, "kind");
        }
    }

    record SpotlightActivated(OnboardingTour.Kind kind)
            implements OnboardingEvent {
        public SpotlightActivated {
            Objects.requireNonNull(kind, "kind");
        }
    }

    record OperationStarted(OperationKind operation, UUID requestId)
            implements OnboardingEvent {
        public OperationStarted {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(requestId, "requestId");
        }
    }

    record OperationCompleted(UUID requestId, boolean succeeded)
            implements OnboardingEvent {
        public OperationCompleted {
            Objects.requireNonNull(requestId, "requestId");
        }
    }

    record SaveCompleted() implements OnboardingEvent {
    }

    enum Direction { NEXT, BACK, SKIP }

    enum ShortcutKind { SAVE, DASHBOARD }

    enum OperationKind { UNDO, REDO, SAVE, RESTORE }
}
