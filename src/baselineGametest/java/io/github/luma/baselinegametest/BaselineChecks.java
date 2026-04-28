package io.github.luma.baselinegametest;

import java.util.ArrayList;
import java.util.List;

final class BaselineChecks {

    private final List<String> failures = new ArrayList<>();
    private int passedCount;

    void check(boolean condition, String label) {
        if (condition) {
            this.passedCount++;
        } else {
            this.failures.add(label);
        }
    }

    int passedCount() {
        return this.passedCount;
    }

    int failedCount() {
        return this.failures.size();
    }

    List<String> failures() {
        return List.copyOf(this.failures);
    }
}
