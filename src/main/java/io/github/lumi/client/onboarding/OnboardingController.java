package io.github.lumi.client.onboarding;

import java.util.Objects;

/** Sole owner of onboarding transitions and their requested UI effects. */
public final class OnboardingController {
    private final OnboardingTour tour;
    private boolean completed;

    public OnboardingController() {
        this(new OnboardingTour());
    }

    OnboardingController(OnboardingTour tour) {
        this.tour = Objects.requireNonNull(tour, "tour");
    }

    public OnboardingTour.Page current() { return tour.current(); }
    public int displayIndex() { return tour.displayIndex(); }
    public boolean canGoBack() { return tour.canGoBack(); }
    public boolean completed() { return completed; }

    public Effect handle(OnboardingEvent event) {
        Objects.requireNonNull(event, "event");
        if (completed) return Effect.NONE;
        return switch (event) {
            case OnboardingEvent.Navigation navigation ->
                    navigate(navigation.direction());
            case OnboardingEvent.Shortcut shortcut -> shortcut(shortcut);
            case OnboardingEvent.WorldCompleted world ->
                    advanceExpected(world.kind(), Effect.REOPEN);
            case OnboardingEvent.SaveCompleted ignored ->
                    advanceExpected(OnboardingTour.Kind.SHORTCUT_SAVE, Effect.REFRESH);
            case OnboardingEvent.SpotlightActivated spotlight ->
                    advanceExpected(spotlight.kind(), Effect.REFRESH);
        };
    }

    private Effect navigate(OnboardingEvent.Direction direction) {
        return switch (direction) {
            case BACK -> {
                tour.movePrevious();
                yield Effect.REFRESH;
            }
            case SKIP -> complete(Effect.COMPLETE);
            case NEXT -> next();
        };
    }

    private Effect next() {
        return switch (current().kind()) {
            case WORLD_EDIT, WORLD_PREVIEW -> Effect.ENTER_WORLD;
            case SHORTCUT_SAVE -> Effect.OPEN_SAVE;
            case SHORTCUT_DASHBOARD -> advance(Effect.OPEN_DASHBOARD);
            case SHORTCUT_HOTKEYS -> complete(Effect.OPEN_HOTKEYS);
            default -> advance(Effect.REFRESH);
        };
    }

    private Effect shortcut(OnboardingEvent.Shortcut event) {
        if (!event.pressed()) return Effect.NONE;
        return switch (event.shortcut()) {
            case SAVE -> current().kind() == OnboardingTour.Kind.SHORTCUT_SAVE
                    ? Effect.OPEN_SAVE : Effect.NONE;
            case DASHBOARD -> current().kind() == OnboardingTour.Kind.SHORTCUT_DASHBOARD
                    ? advance(Effect.OPEN_DASHBOARD) : Effect.NONE;
            case HOTKEYS -> current().kind() == OnboardingTour.Kind.SHORTCUT_HOTKEYS
                    ? complete(Effect.OPEN_HOTKEYS) : Effect.NONE;
            case PREVIEW -> Effect.NONE;
        };
    }

    private Effect advanceExpected(OnboardingTour.Kind expected, Effect effect) {
        return current().kind() == expected ? advance(effect) : Effect.NONE;
    }

    private Effect advance(Effect effect) {
        tour.moveNext();
        return effect;
    }

    private Effect complete(Effect effect) {
        completed = true;
        return effect;
    }

    public enum Effect {
        NONE, REFRESH, REOPEN, ENTER_WORLD, OPEN_SAVE,
        OPEN_DASHBOARD, OPEN_HOTKEYS, COMPLETE
    }
}
