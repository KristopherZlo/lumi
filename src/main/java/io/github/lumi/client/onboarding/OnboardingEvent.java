package io.github.lumi.client.onboarding;

import java.util.Objects;

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

    record SaveCompleted() implements OnboardingEvent { }

    record SpotlightActivated(OnboardingTour.Kind kind)
            implements OnboardingEvent {
        public SpotlightActivated {
            Objects.requireNonNull(kind, "kind");
        }
    }

    enum Direction { NEXT, BACK, SKIP }

    enum ShortcutKind { PREVIEW, SAVE, DASHBOARD, HOTKEYS }
}
