package io.github.lumi.client.ui;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Navigation intents shared by zone history cards and graph nodes. */
public record ZoneHistoryActions(
        Consumer<HistorySnapshotPayload.Version> openDetails,
        Consumer<HistorySnapshotPayload.Version> openRestore,
        Consumer<HistorySnapshotPayload.Version> createBranch,
        BiConsumer<CommitId, VersionTags> updateTags) {
    public ZoneHistoryActions {
        Objects.requireNonNull(openDetails, "openDetails");
        Objects.requireNonNull(openRestore, "openRestore");
        Objects.requireNonNull(createBranch, "createBranch");
        Objects.requireNonNull(updateTags, "updateTags");
    }
}
