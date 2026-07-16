package io.github.lumi.client.ui;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Resolves the selected first-parent history row into one immutable comparison. */
public final class VersionCompareController {
    public Optional<Target> target(
            List<HistorySnapshotPayload.Version> versions, int selectedIndex) {
        Objects.requireNonNull(versions, "versions");
        if (selectedIndex < 0 || selectedIndex + 1 >= versions.size()) {
            return Optional.empty();
        }
        var selected = versions.get(selectedIndex);
        var parent = versions.get(selectedIndex + 1);
        return Optional.of(new Target(parent.id(), selected.id(), selected.message()));
    }

    public record Target(CommitId before, CommitId after, String label) {
        public Target {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
            Objects.requireNonNull(label, "label");
        }
    }
}
