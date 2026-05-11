package io.github.luma.minecraft.testing;

import java.util.List;
import java.util.Objects;

/**
 * Ordered post-interaction wait windows for structure fixture undo/redo checks.
 */
final class StructureFixtureTimingPlan {

    private static final StructureFixtureTimingPlan DEFAULT =
            new StructureFixtureTimingPlan(List.of(1, 10, 20, 40));

    private final List<Integer> waitTicks;

    StructureFixtureTimingPlan(List<Integer> waitTicks) {
        Objects.requireNonNull(waitTicks, "waitTicks");
        if (waitTicks.isEmpty()) {
            throw new IllegalArgumentException("At least one wait timing is required");
        }
        for (Integer waitTick : waitTicks) {
            if (waitTick == null || waitTick <= 0) {
                throw new IllegalArgumentException("Wait timings must be positive ticks");
            }
        }
        this.waitTicks = List.copyOf(waitTicks);
    }

    static StructureFixtureTimingPlan defaultPlan() {
        return DEFAULT;
    }

    boolean exhausted(int timingIndex) {
        return timingIndex >= this.waitTicks.size();
    }

    int waitTicks(int timingIndex) {
        return this.waitTicks.get(timingIndex);
    }

    List<Integer> waitTicks() {
        return this.waitTicks;
    }
}
