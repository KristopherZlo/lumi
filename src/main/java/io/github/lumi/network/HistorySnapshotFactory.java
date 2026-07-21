package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.minecraft.server.level.ServerPlayer;

/** Builds bounded join metadata; history rows are loaded through paged queries. */
final class HistorySnapshotFactory {
    HistorySnapshotPayload create(
            ServerPlayer player, FabricDimensionRuntime runtime) throws IOException {
        long started = System.nanoTime();
        var head = runtime.activeRef();
        var workspace = runtime.activeWorkspace();
        var pending = runtime.pendingPreview(512);
        var workspaceViews = runtime.visibleWorkspaces().stream()
                .map(visible -> new HistorySnapshotPayload.WorkspaceView(
                        visible.id(), visible.name(), visible.id().equals(workspace.id()),
                        visible.bounds().isPresent(),
                        visible.settings().hideZoneCommits(),
                        visible.settings().includeEntitiesOnRestore(),
                        visible.settings().previewGenerationEnabled(),
                        visible.settings().workspaceHudEnabled(),
                        visible.settings().automaticVersionsEnabled()))
                .toList();
        var versions = java.util.List.<HistorySnapshotPayload.Version>of();
        var branchViews = runtime.visibleBranches().stream()
                .map(ref -> new HistorySnapshotPayload.Branch(
                        ref.name().value(), ref.commit(), ref.name().equals(head.name())))
                .toList();
        var visibleZones = runtime.visibleZones().stream().limit(64).toList();
        var sharedCells = new io.github.lumi.domain.service.ZoneOverlapCounter()
                .count(visibleZones);
        var activeZoneIds = visibleZones.stream()
                .filter(zone -> zone.activeActors().contains(player.getUUID()))
                .map(io.github.lumi.domain.model.Zone::id)
                .collect(Collectors.toSet());
        var zoneHistories = runtime.zoneHistories(activeZoneIds, 1);
        var zoneViews = visibleZones.stream()
                .map(zone -> new HistorySnapshotPayload.ZoneView(
                        zone.id(), zone.name(), zone.color(), zone.cells().size(),
                        sharedCells.getOrDefault(zone.id(), 0),
                        zone.revision(), zone.activeActors().contains(player.getUUID()),
                        zoneHistories.getOrDefault(zone.id(), java.util.List.of()).stream()
                                .map(entry -> new HistorySnapshotPayload.Version(
                                        entry.id(), runtime.versionDisplayName(
                                                entry.id(), entry.commit().message()),
                                        entry.commit().author().name(),
                                        entry.commit().timestamp().toEpochMilli(),
                                        entry.commit().kind(),
                                        runtime.versionTags(entry.id()),
                                        entry.commit().parents(), entry.commit().statistics(),
                                        entry.commit().zoneId()))
                                .toList()))
                .toList();
        var deleted = runtime.deletedVersions(64).stream()
                .map(entry -> new HistorySnapshotPayload.Version(
                        entry.id(), runtime.versionDisplayName(
                                entry.id(), entry.commit().message()),
                        entry.commit().author().name(),
                        entry.commit().timestamp().toEpochMilli(),
                        entry.commit().kind(), runtime.versionTags(entry.id()),
                        entry.commit().parents(), entry.commit().statistics(),
                        entry.commit().zoneId()))
                .toList();
        HistorySnapshotPayload snapshot = new HistorySnapshotPayload(
                runtime.level().dimension().identifier().toString(),
                head.commit(), head.revision(), pending.totalKeys(),
                pending.blocks().stream()
                        .map(block -> new HistorySnapshotPayload.PendingBlock(
                                block.x(), block.y(), block.z()))
                        .toList(),
                pending.bounds(),
                runtime.operations().hasActiveOperation(),
                runtime.recoveryJournal().isPresent(),
                workspace.id(), workspace.name(), head.name().value(),
                workspaceViews, versions, branchViews, zoneViews, deleted);
        LumiMod.LOGGER.info(
                "Lumi prepared history snapshot in {} ms "
                        + "(versions={}, zones={}, deleted={})",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                versions.size(), zoneViews.size(), deleted.size());
        return snapshot;
    }
}
