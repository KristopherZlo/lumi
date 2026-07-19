package io.github.lumi.client;

import io.github.lumi.LumiMod;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.client.state.ClientCompareStore;
import io.github.lumi.client.state.ClientZoneOverlayStore;
import io.github.lumi.client.state.ClientPendingStatisticsStore;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.WorkspaceSettings;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.domain.model.VersionDisplayName;
import io.github.lumi.network.HistoryCommandPayload;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistoryPageRequestPayload;
import io.github.lumi.network.BranchCreateArgument;
import io.github.lumi.network.CompareArgument;
import io.github.lumi.network.CompareResultPayload;
import io.github.lumi.network.CleanupResultPayload;
import io.github.lumi.network.MergeArgument;
import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.network.OperationCancelPayload;
import io.github.lumi.network.PartialRestoreArgument;
import io.github.lumi.network.PartialRestorePlanPayload;
import io.github.lumi.network.PendingStatisticsPayload;
import io.github.lumi.network.PendingStatisticsRequestPayload;
import io.github.lumi.network.QuickRollbackArgument;
import io.github.lumi.network.SaveArgument;
import io.github.lumi.network.PackageInspectionPayload;
import io.github.lumi.network.ZoneCreateArgument;
import io.github.lumi.network.ZoneCellsArgument;
import io.github.lumi.network.ZoneDeleteArgument;
import io.github.lumi.network.ZoneOverlayArgument;
import io.github.lumi.network.ZoneOverlayPayload;
import io.github.lumi.network.ZoneCompareArgument;
import io.github.lumi.network.ZoneRestoreArgument;
import io.github.lumi.network.ZoneSaveArgument;
import io.github.lumi.network.WorkspaceCreateArgument;
import io.github.lumi.network.WorkspaceSettingsArgument;
import io.github.lumi.network.VersionTagsArgument;
import io.github.lumi.network.VersionRenameArgument;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Thin client sender/receiver; all history decisions stay on the server. */
public final class LumiClientNetworking {
    private final ClientHistoryStore history;
    private final ClientHistoryPageStore historyPages;
    private final ClientCompareStore comparisons;
    private final ClientZoneOverlayStore zoneOverlays;
    private final ClientPendingStatisticsStore pendingStatistics;
    private final Consumer<HistorySnapshotPayload> snapshotListener;
    private final Consumer<OperationEventPayload> eventListener;
    private final Consumer<CompareResultPayload> compareListener;
    private final Consumer<PackageInspectionPayload> packageListener;
    private final Consumer<CleanupResultPayload> cleanupListener;
    private final Consumer<PartialRestorePlanPayload> partialRestoreListener;

    public LumiClientNetworking(
            ClientHistoryStore history,
            ClientHistoryPageStore historyPages,
            ClientCompareStore comparisons,
            ClientZoneOverlayStore zoneOverlays,
            ClientPendingStatisticsStore pendingStatistics,
            Consumer<HistorySnapshotPayload> snapshotListener,
            Consumer<OperationEventPayload> eventListener,
            Consumer<CompareResultPayload> compareListener,
            Consumer<PackageInspectionPayload> packageListener,
            Consumer<CleanupResultPayload> cleanupListener,
            Consumer<PartialRestorePlanPayload> partialRestoreListener) {
        this.history = Objects.requireNonNull(history, "history");
        this.historyPages = Objects.requireNonNull(historyPages, "historyPages");
        this.comparisons = Objects.requireNonNull(comparisons, "comparisons");
        this.zoneOverlays = Objects.requireNonNull(zoneOverlays, "zoneOverlays");
        this.pendingStatistics = Objects.requireNonNull(
                pendingStatistics, "pendingStatistics");
        this.snapshotListener = Objects.requireNonNull(snapshotListener, "snapshotListener");
        this.eventListener = Objects.requireNonNull(eventListener, "eventListener");
        this.compareListener = Objects.requireNonNull(compareListener, "compareListener");
        this.packageListener = Objects.requireNonNull(packageListener, "packageListener");
        this.cleanupListener = Objects.requireNonNull(cleanupListener, "cleanupListener");
        this.partialRestoreListener = Objects.requireNonNull(
                partialRestoreListener, "partialRestoreListener");
    }

    public void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                HistorySnapshotPayload.TYPE, (payload, context) ->
                        context.client().execute(() -> {
                            history.accept(payload);
                            snapshotListener.accept(payload);
                        }));
        ClientPlayNetworking.registerGlobalReceiver(
                OperationEventPayload.TYPE, (payload, context) ->
                        context.client().execute(() -> {
                            history.accept(payload);
                            eventListener.accept(payload);
                        }));
        ClientPlayNetworking.registerGlobalReceiver(
                CompareResultPayload.TYPE, (payload, context) ->
                        context.client().execute(() -> {
                            if (comparisons.accept(payload)) {
                                compareListener.accept(payload);
                            }
                        }));
        ClientPlayNetworking.registerGlobalReceiver(
                HistoryPagePayload.TYPE, (payload, context) ->
                        context.client().execute(() -> historyPages.accept(payload)));
        ClientPlayNetworking.registerGlobalReceiver(
                PendingStatisticsPayload.TYPE, (payload, context) ->
                        context.client().execute(() ->
                                pendingStatistics.accept(payload)));
        ClientPlayNetworking.registerGlobalReceiver(
                PackageInspectionPayload.TYPE, (payload, context) ->
                        context.client().execute(() -> packageListener.accept(payload)));
        ClientPlayNetworking.registerGlobalReceiver(
                CleanupResultPayload.TYPE, (payload, context) ->
                        context.client().execute(() -> cleanupListener.accept(payload)));
        ClientPlayNetworking.registerGlobalReceiver(
                PartialRestorePlanPayload.TYPE, (payload, context) ->
                        context.client().execute(() -> partialRestoreListener.accept(payload)));
        ClientPlayNetworking.registerGlobalReceiver(
                ZoneOverlayPayload.TYPE, (payload, context) ->
                        context.client().execute(() -> zoneOverlays.accept(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            history.clear();
            historyPages.clear();
            pendingStatistics.clear();
            comparisons.clear();
            zoneOverlays.clear();
        });
    }

    public UUID save(String message) {
        return save(message, VersionTags.empty());
    }

    public UUID save(String message, VersionTags tags) {
        return send(HistoryCommandPayload.Kind.SAVE,
                new SaveArgument(
                        Objects.requireNonNull(message, "message"),
                        Objects.requireNonNull(tags, "tags")).encode());
    }

    public UUID amend(String message) {
        return amend(message, VersionTags.empty());
    }

    public UUID amend(String message, VersionTags tags) {
        return send(HistoryCommandPayload.Kind.AMEND,
                new SaveArgument(
                        Objects.requireNonNull(message, "message"),
                        Objects.requireNonNull(tags, "tags")).encode());
    }

    public UUID restore(CommitId target) {
        return send(HistoryCommandPayload.Kind.RESTORE,
                Objects.requireNonNull(target, "target").hex());
    }

    public UUID restoreWithoutEntities(CommitId target) {
        return send(HistoryCommandPayload.Kind.RESTORE_NO_ENTITIES,
                Objects.requireNonNull(target, "target").hex());
    }

    public UUID previewRestoreArea(CommitId target, BlockAreaTarget area) {
        return send(HistoryCommandPayload.Kind.RESTORE_AREA_PLAN,
                new PartialRestoreArgument(
                        Objects.requireNonNull(target, "target"),
                        Objects.requireNonNull(area, "area")).encode());
    }

    public UUID applyRestoreArea(UUID previewToken) {
        return send(HistoryCommandPayload.Kind.RESTORE_AREA_APPLY,
                Objects.requireNonNull(previewToken, "previewToken").toString());
    }

    public UUID quickRollback() {
        return quickRollback(Optional.empty());
    }

    public UUID quickRollback(Optional<BlockBox> selection) {
        return send(HistoryCommandPayload.Kind.QUICK_ROLLBACK,
                new QuickRollbackArgument(selection).encode());
    }

    public UUID undo() {
        return send(HistoryCommandPayload.Kind.UNDO, "");
    }

    public UUID redo() {
        return send(HistoryCommandPayload.Kind.REDO, "");
    }

    public UUID switchBranch(String branchName) {
        return send(HistoryCommandPayload.Kind.BRANCH_SWITCH,
                Objects.requireNonNull(branchName, "branchName"));
    }

    public UUID createBranch(String branchName) {
        return send(HistoryCommandPayload.Kind.BRANCH_CREATE,
                Objects.requireNonNull(branchName, "branchName"));
    }

    public UUID createBranchAt(String branchName, CommitId startingPoint) {
        return send(HistoryCommandPayload.Kind.BRANCH_CREATE_AT,
                new BranchCreateArgument(
                        new io.github.lumi.domain.model.BranchName(
                                Objects.requireNonNull(branchName, "branchName")),
                        Objects.requireNonNull(startingPoint, "startingPoint")).encode());
    }

    public UUID deleteBranch(String branchName) {
        return send(HistoryCommandPayload.Kind.BRANCH_DELETE,
                Objects.requireNonNull(branchName, "branchName"));
    }

    public UUID createWorkspace(String name, Optional<BlockBox> bounds) {
        return send(HistoryCommandPayload.Kind.WORKSPACE_CREATE,
                new WorkspaceCreateArgument(
                        Objects.requireNonNull(name, "name"),
                        Objects.requireNonNull(bounds, "bounds")).encode());
    }

    public UUID switchWorkspace(UUID workspaceId) {
        return send(HistoryCommandPayload.Kind.WORKSPACE_SWITCH,
                Objects.requireNonNull(workspaceId, "workspaceId").toString());
    }

    public UUID updateWorkspaceSettings(WorkspaceSettings settings) {
        return send(HistoryCommandPayload.Kind.WORKSPACE_SETTINGS,
                new WorkspaceSettingsArgument(
                        Objects.requireNonNull(settings, "settings")).encode());
    }

    public UUID createZone(String name) {
        return send(HistoryCommandPayload.Kind.ZONE_CREATE,
                new ZoneCreateArgument(
                        Objects.requireNonNull(name, "name")).encode());
    }

    public UUID enterZone(UUID zoneId) {
        return send(HistoryCommandPayload.Kind.ZONE_ENTER,
                Objects.requireNonNull(zoneId, "zoneId").toString());
    }

    public UUID leaveZone(UUID zoneId) {
        return send(HistoryCommandPayload.Kind.ZONE_LEAVE,
                Objects.requireNonNull(zoneId, "zoneId").toString());
    }

    public UUID saveZone(UUID zoneId, String message) {
        return saveZone(zoneId, message, VersionTags.empty());
    }

    public UUID editActiveZone(boolean add, BlockBox area) {
        return send(HistoryCommandPayload.Kind.ZONE_CELLS,
                new ZoneCellsArgument(
                        add, Objects.requireNonNull(area, "area")).encode());
    }

    public UUID deleteZone(UUID zoneId, long expectedRevision) {
        return send(HistoryCommandPayload.Kind.ZONE_DELETE,
                new ZoneDeleteArgument(
                        Objects.requireNonNull(zoneId, "zoneId"),
                        expectedRevision).encode());
    }

    public UUID saveZone(UUID zoneId, String message, VersionTags tags) {
        return send(HistoryCommandPayload.Kind.ZONE_SAVE,
                new ZoneSaveArgument(
                        Objects.requireNonNull(zoneId, "zoneId"),
                        Objects.requireNonNull(message, "message"),
                        Objects.requireNonNull(tags, "tags")).encode());
    }

    public UUID amendZone(UUID zoneId, String message, VersionTags tags) {
        return send(HistoryCommandPayload.Kind.ZONE_AMEND,
                new ZoneSaveArgument(
                        Objects.requireNonNull(zoneId, "zoneId"),
                        Objects.requireNonNull(message, "message"),
                        Objects.requireNonNull(tags, "tags")).encode());
    }

    public UUID restoreZone(UUID zoneId, CommitId target) {
        return send(HistoryCommandPayload.Kind.ZONE_RESTORE,
                new ZoneRestoreArgument(
                        Objects.requireNonNull(zoneId, "zoneId"),
                        Objects.requireNonNull(target, "target")).encode());
    }

    public UUID deleteVersion(CommitId target) {
        return send(HistoryCommandPayload.Kind.DELETE_VERSION,
                Objects.requireNonNull(target, "target").hex());
    }

    public UUID cleanupVersion(CommitId target) {
        return send(HistoryCommandPayload.Kind.CLEANUP_VERSION,
                Objects.requireNonNull(target, "target").hex());
    }

    public UUID inspectCleanup() {
        return send(HistoryCommandPayload.Kind.CLEANUP_INSPECT, "");
    }

    public UUID applyCleanup() {
        return send(HistoryCommandPayload.Kind.CLEANUP_APPLY, "");
    }

    public UUID restoreDeletedVersion(CommitId target) {
        return send(HistoryCommandPayload.Kind.RESTORE_DELETED_VERSION,
                Objects.requireNonNull(target, "target").hex());
    }

    public UUID updateVersionTags(CommitId target, VersionTags tags) {
        return send(HistoryCommandPayload.Kind.UPDATE_VERSION_TAGS,
                new VersionTagsArgument(
                        Objects.requireNonNull(target, "target"),
                        Objects.requireNonNull(tags, "tags")).encode());
    }

    public UUID renameVersion(CommitId target, String replacement) {
        return send(HistoryCommandPayload.Kind.RENAME_VERSION,
                new VersionRenameArgument(
                        Objects.requireNonNull(target, "target"),
                        new VersionDisplayName(replacement)).encode());
    }

    public UUID exportPackage(String name) {
        return send(HistoryCommandPayload.Kind.PACKAGE_EXPORT,
                Objects.requireNonNull(name, "name"));
    }

    public UUID inspectPackage(String name) {
        return send(HistoryCommandPayload.Kind.PACKAGE_INSPECT,
                Objects.requireNonNull(name, "name"));
    }

    public UUID importPackage(UUID inspectionToken) {
        return send(HistoryCommandPayload.Kind.PACKAGE_IMPORT,
                Objects.requireNonNull(inspectionToken, "inspectionToken").toString());
    }

    public UUID merge(String sourceBranch) {
        String source = Objects.requireNonNull(sourceBranch, "sourceBranch");
        int slash = source.lastIndexOf('/');
        String shortName = slash < 0 ? source : source.substring(slash + 1);
        return send(HistoryCommandPayload.Kind.MERGE,
                new MergeArgument(source, "Merge " + shortName).encode());
    }

    public UUID compare(CommitId before, CommitId after) {
        CompareArgument argument = new CompareArgument(
                Objects.requireNonNull(before, "before"),
                Objects.requireNonNull(after, "after"));
        return beginCompare(
                HistoryCommandPayload.Kind.COMPARE, argument.encode(),
                argument.before(), argument.after());
    }

    public UUID compareZone(UUID zoneId, CommitId before, CommitId after) {
        ZoneCompareArgument argument = new ZoneCompareArgument(
                Objects.requireNonNull(zoneId, "zoneId"),
                Objects.requireNonNull(before, "before"),
                Objects.requireNonNull(after, "after"));
        return beginCompare(
                HistoryCommandPayload.Kind.ZONE_COMPARE, argument.encode(),
                argument.before(), argument.after());
    }

    private UUID beginCompare(
            HistoryCommandPayload.Kind kind,
            String argument,
            CommitId before,
            CommitId after) {
        var snapshot = history.state().snapshot().orElseThrow(
                () -> new IllegalStateException("Lumi history has not synchronized yet"));
        if (!ClientPlayNetworking.canSend(HistoryCommandPayload.TYPE)) {
            throw new IllegalStateException("The connected server does not support Lumi history");
        }
        UUID requestId = UUID.randomUUID();
        comparisons.begin(
                requestId, snapshot.dimensionId(), before, after);
        sendCommand(requestId, kind, argument, snapshot);
        return requestId;
    }

    public UUID cancelCompare(UUID requestId) {
        comparisons.clear();
        return send(HistoryCommandPayload.Kind.COMPARE_CANCEL,
                Objects.requireNonNull(requestId, "requestId").toString());
    }

    public UUID resumeRecovery() {
        return send(HistoryCommandPayload.Kind.RECOVER_RESUME, "");
    }

    public UUID returnRecovery() {
        return send(HistoryCommandPayload.Kind.RECOVER_RETURN, "");
    }

    public UUID refreshSnapshot() {
        return send(HistoryCommandPayload.Kind.SNAPSHOT_REFRESH, "");
    }

    public UUID requestHistoryPage(
            io.github.lumi.domain.model.BranchName branch,
            Optional<UUID> zoneId,
            int offset,
            int limit) {
        return requestHistoryPage(
                Optional.empty(), branch, zoneId, offset, limit, "");
    }

    public UUID requestPendingStatistics() {
        var snapshot = history.state().snapshot().orElseThrow(
                () -> new IllegalStateException(
                        "Lumi history has not synchronized yet"));
        if (!ClientPlayNetworking.canSend(
                PendingStatisticsRequestPayload.TYPE)) {
            throw new IllegalStateException(
                    "The connected server does not support pending statistics");
        }
        UUID requestId = UUID.randomUUID();
        pendingStatistics.begin(requestId, snapshot);
        ClientPlayNetworking.send(new PendingStatisticsRequestPayload(
                requestId, snapshot.dimensionId(), snapshot.workspaceId(),
                snapshot.head(), snapshot.revision()));
        return requestId;
    }

    public UUID requestHistoryPage(
            io.github.lumi.domain.model.BranchName branch,
            Optional<UUID> zoneId,
            int offset,
            int limit,
            String query) {
        return requestHistoryPage(
                Optional.empty(), branch, zoneId, offset, limit, query);
    }

    public UUID requestHistoryPage(
            ClientHistoryPageStore.Channel channel,
            io.github.lumi.domain.model.BranchName branch,
            Optional<UUID> zoneId,
            int offset,
            int limit) {
        return requestHistoryPage(
                Optional.of(Objects.requireNonNull(channel, "channel")),
                branch, zoneId, offset, limit, "");
    }

    private UUID requestHistoryPage(
            Optional<ClientHistoryPageStore.Channel> channel,
            io.github.lumi.domain.model.BranchName branch,
            Optional<UUID> zoneId,
            int offset,
            int limit,
            String query) {
        var snapshot = history.state().snapshot().orElseThrow(
                () -> new IllegalStateException(
                        "Lumi history has not synchronized yet"));
        if (!ClientPlayNetworking.canSend(HistoryPageRequestPayload.TYPE)) {
            throw new IllegalStateException(
                    "The connected server does not support paged history");
        }
        UUID requestId = UUID.randomUUID();
        BranchName requestedBranch = Objects.requireNonNull(branch, "branch");
        Optional<UUID> requestedZone = Objects.requireNonNull(zoneId, "zoneId");
        String requestedQuery = Objects.requireNonNull(query, "query");
        if (channel.isPresent()) {
            historyPages.begin(
                    channel.orElseThrow(), requestId,
                    snapshot.dimensionId(), snapshot.workspaceId(),
                    requestedBranch, requestedZone, offset);
        } else {
            historyPages.begin(
                    requestId, snapshot.dimensionId(), snapshot.workspaceId(),
                    requestedBranch, requestedZone, offset);
        }
        ClientPlayNetworking.send(new HistoryPageRequestPayload(
                requestId, snapshot.dimensionId(), snapshot.workspaceId(),
                requestedBranch, requestedZone, offset, limit, requestedQuery));
        return requestId;
    }

    public UUID requestZoneOverlay(ZoneOverlayArgument.Mode mode) {
        var snapshot = history.state().snapshot().orElseThrow(
                () -> new IllegalStateException(
                        "Lumi history has not synchronized yet"));
        UUID requestId = UUID.randomUUID();
        zoneOverlays.begin(
                requestId, snapshot.dimensionId(), snapshot.workspaceId());
        sendCommand(
                requestId, HistoryCommandPayload.Kind.ZONE_OVERLAY,
                new ZoneOverlayArgument(
                        Objects.requireNonNull(mode, "mode")).encode(),
                snapshot);
        return requestId;
    }

    public UUID cancel(UUID originalRequest) {
        var event = history.state().events().get(
                Objects.requireNonNull(originalRequest, "originalRequest"));
        if (event == null || event.ticketId().isEmpty()) {
            throw new IllegalStateException("Operation has no cancellable queue ticket");
        }
        if (!ClientPlayNetworking.canSend(OperationCancelPayload.TYPE)) {
            throw new IllegalStateException("The connected server does not support queue cancellation");
        }
        UUID requestId = UUID.randomUUID();
        LumiMod.LOGGER.info(
                "Lumi client cancellation sent: id={}, ticket={}",
                requestId, event.ticketId().orElseThrow());
        ClientPlayNetworking.send(new OperationCancelPayload(
                requestId, event.ticketId().orElseThrow()));
        return requestId;
    }

    private UUID send(HistoryCommandPayload.Kind kind, String argument) {
        var snapshot = history.state().snapshot().orElseThrow(
                () -> new IllegalStateException("Lumi history has not synchronized yet"));
        if (!ClientPlayNetworking.canSend(HistoryCommandPayload.TYPE)) {
            throw new IllegalStateException("The connected server does not support Lumi history");
        }
        UUID requestId = UUID.randomUUID();
        sendCommand(requestId, kind, argument, snapshot);
        return requestId;
    }

    private static void sendCommand(
            UUID requestId,
            HistoryCommandPayload.Kind kind,
            String argument,
            HistorySnapshotPayload snapshot) {
        LumiMod.LOGGER.info(
                "Lumi client request sent: id={}, action={}, dimension={}, revision={}",
                requestId, kind, snapshot.dimensionId(), snapshot.revision());
        ClientPlayNetworking.send(new HistoryCommandPayload(
                requestId, kind, argument, snapshot.head(), snapshot.revision()));
    }
}
