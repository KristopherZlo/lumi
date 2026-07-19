package io.github.lumi.client.ui;

import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.function.Consumer;

/** Navigation intents shared by zone history cards and graph nodes. */
public record ZoneHistoryActions(
        Consumer<HistorySnapshotPayload.Version> openDetails,
        Consumer<HistorySnapshotPayload.Version> openRestore,
        Consumer<HistorySnapshotPayload.Version> createBranch,
        Consumer<VersionCompareController.Target> openCompare) {
    public ZoneHistoryActions {
        Objects.requireNonNull(openDetails, "openDetails");
        Objects.requireNonNull(openRestore, "openRestore");
        Objects.requireNonNull(createBranch, "createBranch");
        Objects.requireNonNull(openCompare, "openCompare");
    }
}
