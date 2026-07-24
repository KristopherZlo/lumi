package io.github.lumi.client.ui;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Vertical content motion for one project-page navigation. */
final class LumiPageTransition {
    private static final int EXIT_MILLIS = 90;
    private static final int ENTER_MILLIS = 140;
    private static final float DISTANCE = 10.0F;
    private final LumiMotion motion;
    private Phase phase = Phase.IDLE;
    private ProjectTab destination;

    LumiPageTransition() {
        motion = new LumiMotion();
    }

    LumiPageTransition(LongSupplier nanoTime) {
        motion = new LumiMotion(nanoTime);
    }

    void enter() {
        phase = Phase.ENTERING;
        destination = null;
        motion.start(ENTER_MILLIS);
    }

    boolean exit(ProjectTab destination) {
        Objects.requireNonNull(destination, "destination");
        if (phase != Phase.IDLE) return false;
        this.destination = destination;
        phase = Phase.EXITING;
        motion.start(EXIT_MILLIS);
        return true;
    }

    boolean active() {
        return phase != Phase.IDLE;
    }

    Frame frame() {
        if (phase == Phase.IDLE) return Frame.IDLE;
        float value = motion.value();
        if (phase == Phase.ENTERING) {
            if (value >= 1.0F) phase = Phase.IDLE;
            return new Frame(
                    DISTANCE * (1.0F - value), value, Optional.empty());
        }
        Optional<ProjectTab> completed = value >= 1.0F
                ? Optional.of(destination) : Optional.empty();
        if (completed.isPresent()) {
            destination = null;
            phase = Phase.IDLE;
        }
        return new Frame(-DISTANCE * value, 1.0F - value, completed);
    }

    record Frame(
            float offsetY,
            float opacity,
            Optional<ProjectTab> completedDestination) {
        private static final Frame IDLE =
                new Frame(0.0F, 1.0F, Optional.empty());
    }

    private enum Phase {
        IDLE,
        ENTERING,
        EXITING
    }
}
