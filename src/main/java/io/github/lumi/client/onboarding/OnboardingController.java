package io.github.lumi.client.onboarding;

import java.util.Objects;
import java.util.UUID;

/** Sole owner of onboarding transitions and their requested UI effects. */
public final class OnboardingController {
    private final OnboardingTour tour;
    private UndoRedoPhase undoRedoPhase = UndoRedoPhase.UNDO;
    private OnboardingEvent.OperationKind pendingOperation;
    private UUID pendingRequestId;
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
    public UndoRedoPhase undoRedoPhase() { return undoRedoPhase; }
    public boolean waitingForOperation() { return pendingRequestId != null; }

    public Effect handle(OnboardingEvent event) {
        Objects.requireNonNull(event, "event");
        if (completed) return Effect.NONE;
        return switch (event) {
            case OnboardingEvent.Navigation navigation ->
                    navigate(navigation.direction());
            case OnboardingEvent.Shortcut shortcut -> shortcut(shortcut);
            case OnboardingEvent.WorldCompleted world ->
                    worldCompleted(world.kind());
            case OnboardingEvent.SpotlightActivated spotlight ->
                    advanceExpected(spotlight.kind(), Effect.REFRESH);
            case OnboardingEvent.OperationStarted operation ->
                    operationStarted(operation);
            case OnboardingEvent.OperationCompleted operation ->
                    operationCompleted(operation);
            case OnboardingEvent.SaveCompleted ignored ->
                    current().kind() == OnboardingTour.Kind.SHORTCUT_SAVE
                            ? advance(Effect.ENTER_WORLD) : Effect.NONE;
        };
    }

    private Effect navigate(OnboardingEvent.Direction direction) {
        return switch (direction) {
            case BACK -> {
                clearPendingOperation();
                tour.movePrevious();
                if (current().kind() == OnboardingTour.Kind.WORLD_UNDO_REDO) {
                    undoRedoPhase = UndoRedoPhase.UNDO;
                }
                yield current().worldStep() ? Effect.ENTER_WORLD : Effect.REFRESH;
            }
            case SKIP -> complete(Effect.COMPLETE);
            case NEXT -> next();
        };
    }

    private Effect next() {
        return switch (current().kind()) {
            case INFO -> advance(Effect.ENTER_WORLD);
            case INFO_MORE -> complete(Effect.COMPLETE);
            default -> Effect.NONE;
        };
    }

    private Effect shortcut(OnboardingEvent.Shortcut event) {
        if (!event.pressed()) return Effect.NONE;
        return switch (event.shortcut()) {
            case SAVE -> current().kind() == OnboardingTour.Kind.SHORTCUT_SAVE
                    ? Effect.OPEN_SAVE : Effect.NONE;
            case DASHBOARD -> current().kind() == OnboardingTour.Kind.SHORTCUT_DASHBOARD
                    ? advance(Effect.OPEN_DASHBOARD) : Effect.NONE;
        };
    }

    private Effect worldCompleted(OnboardingTour.Kind kind) {
        if (current().kind() != kind) return Effect.NONE;
        return switch (kind) {
            case WORLD_EDIT, WORLD_PREVIEW -> advance(Effect.NONE);
            case WORLD_EXPERIMENT -> advance(Effect.REOPEN);
            default -> Effect.NONE;
        };
    }

    private Effect operationStarted(OnboardingEvent.OperationStarted event) {
        if (pendingRequestId != null || !expects(event.operation())) {
            return Effect.NONE;
        }
        pendingOperation = event.operation();
        pendingRequestId = event.requestId();
        return Effect.NONE;
    }

    private Effect operationCompleted(OnboardingEvent.OperationCompleted event) {
        if (!event.requestId().equals(pendingRequestId)) return Effect.NONE;
        OnboardingEvent.OperationKind operation = pendingOperation;
        clearPendingOperation();
        if (!event.succeeded()) return Effect.NONE;
        return switch (operation) {
            case UNDO -> {
                undoRedoPhase = UndoRedoPhase.REDO;
                yield Effect.NONE;
            }
            case REDO -> advance(Effect.REOPEN);
            case SAVE -> advance(Effect.ENTER_WORLD);
            case RESTORE -> advance(Effect.REFRESH);
        };
    }

    private boolean expects(OnboardingEvent.OperationKind operation) {
        return switch (operation) {
            case UNDO -> current().kind() == OnboardingTour.Kind.WORLD_UNDO_REDO
                    && undoRedoPhase == UndoRedoPhase.UNDO;
            case REDO -> current().kind() == OnboardingTour.Kind.WORLD_UNDO_REDO
                    && undoRedoPhase == UndoRedoPhase.REDO;
            case SAVE -> current().kind() == OnboardingTour.Kind.SHORTCUT_SAVE;
            case RESTORE -> current().kind() == OnboardingTour.Kind.SPOTLIGHT_RESTORE;
        };
    }

    private void clearPendingOperation() {
        pendingOperation = null;
        pendingRequestId = null;
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
        OPEN_DASHBOARD, COMPLETE
    }

    public enum UndoRedoPhase { UNDO, REDO }
}
