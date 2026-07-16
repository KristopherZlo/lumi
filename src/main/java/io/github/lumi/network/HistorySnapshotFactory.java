package io.github.lumi.network;

import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.io.IOException;
import java.util.stream.Collectors;
import net.minecraft.server.level.ServerPlayer;

/** Builds one bounded player-specific view from dimension-owned history state. */
final class HistorySnapshotFactory {
    HistorySnapshotPayload create(
            ServerPlayer player, FabricDimensionRuntime runtime) throws IOException {
        var head = runtime.activeRef();
        var workspace = runtime.activeWorkspace();
        var pending = runtime.pendingPreview(512);
        var workspaceViews = runtime.visibleWorkspaces().stream()
                .map(visible -> new HistorySnapshotPayload.WorkspaceView(
                        visible.id(), visible.name(), visible.id().equals(workspace.id()),
                        visible.bounds().isPresent(),
                        visible.settings().hideZoneCommits(),
                        visible.settings().includeEntitiesOnRestore()))
                .toList();
        var versions = runtime.history(32).stream()
                .map(entry -> new HistorySnapshotPayload.Version(
                        entry.id(), entry.commit().message(),
                        entry.commit().author().name(),
                        entry.commit().timestamp().toEpochMilli(),
                        entry.commit().kind()))
                .toList();
        var branchViews = runtime.visibleBranches().stream()
                .map(ref -> new HistorySnapshotPayload.Branch(
                        ref.name().value(), ref.commit(), ref.name().equals(head.name())))
                .toList();
        var visibleZones = runtime.visibleZones().stream().limit(64).toList();
        var zoneHistories = runtime.zoneHistories(
                visibleZones.stream().map(io.github.lumi.domain.model.Zone::id)
                        .collect(Collectors.toSet()),
                8);
        var zoneViews = visibleZones.stream()
                .map(zone -> new HistorySnapshotPayload.ZoneView(
                        zone.id(), zone.name(), zone.color(), zone.cells().size(),
                        zone.revision(), zone.activeActors().contains(player.getUUID()),
                        zoneHistories.getOrDefault(zone.id(), java.util.List.of()).stream()
                                .map(entry -> new HistorySnapshotPayload.Version(
                                        entry.id(), entry.commit().message(),
                                        entry.commit().author().name(),
                                        entry.commit().timestamp().toEpochMilli(),
                                        entry.commit().kind()))
                                .toList()))
                .toList();
        var deleted = runtime.deletedVersions(64).stream()
                .map(entry -> new HistorySnapshotPayload.Version(
                        entry.id(), entry.commit().message(),
                        entry.commit().author().name(),
                        entry.commit().timestamp().toEpochMilli(),
                        entry.commit().kind()))
                .toList();
        return new HistorySnapshotPayload(
                runtime.level().dimension().identifier().toString(),
                head.commit(), head.revision(), pending.totalKeys(),
                pending.blocks().stream()
                        .map(block -> new HistorySnapshotPayload.PendingBlock(
                                block.x(), block.y(), block.z()))
                        .toList(),
                runtime.operations().hasActiveOperation(),
                runtime.recoveryJournal().isPresent(),
                workspace.id(), workspace.name(), head.name().value(),
                workspaceViews, versions, branchViews, zoneViews, deleted);
    }
}
