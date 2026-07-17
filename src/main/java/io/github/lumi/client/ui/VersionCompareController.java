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
        return target(versions, selectedIndex + 1, selectedIndex);
    }

    public Optional<Target> target(
            List<HistorySnapshotPayload.Version> versions,
            int beforeIndex,
            int afterIndex) {
        Objects.requireNonNull(versions, "versions");
        if (beforeIndex < 0 || beforeIndex >= versions.size()
                || afterIndex < 0 || afterIndex >= versions.size()
                || beforeIndex == afterIndex) {
            return Optional.empty();
        }
        var before = versions.get(beforeIndex);
        var after = versions.get(afterIndex);
        if (before.id().equals(after.id())) {
            return Optional.empty();
        }
        return Optional.of(new Target(before.id(), after.id(), after.message()));
    }

    public record Target(CommitId before, CommitId after, String label) {
        public Target {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
            Objects.requireNonNull(label, "label");
        }
    }
}
