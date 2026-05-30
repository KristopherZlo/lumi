package io.github.luma.domain.service;

import io.github.luma.domain.model.ProjectVersion;
import java.util.ArrayList;
import java.util.List;

record DirectRestorePatchPlan(List<ProjectVersion> reverseVersions, List<ProjectVersion> forwardVersions) {

    static DirectRestorePatchPlan empty() {
        return new DirectRestorePatchPlan(List.of(), List.of());
    }

    DirectRestorePatchPlan {
        reverseVersions = reverseVersions == null ? List.of() : List.copyOf(reverseVersions);
        forwardVersions = forwardVersions == null ? List.of() : List.copyOf(forwardVersions);
    }

    int stepCount() {
        return this.reverseVersions.size() + this.forwardVersions.size();
    }

    boolean isDivergent() {
        return !this.reverseVersions.isEmpty() && !this.forwardVersions.isEmpty();
    }

    List<ProjectVersion> allVersions() {
        List<ProjectVersion> versions = new ArrayList<>(this.stepCount());
        versions.addAll(this.reverseVersions);
        versions.addAll(this.forwardVersions);
        return List.copyOf(versions);
    }

    String modeLabel() {
        if (this.reverseVersions.isEmpty() && this.forwardVersions.isEmpty()) {
            return "no-op";
        }
        if (this.reverseVersions.isEmpty()) {
            return "forward";
        }
        if (this.forwardVersions.isEmpty()) {
            return "reverse";
        }
        return "divergent";
    }
}
