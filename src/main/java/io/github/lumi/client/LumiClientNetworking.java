package io.github.lumi.client;

import io.github.lumi.LumiMod;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientCompareStore;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.WorkspaceSettings;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.network.HistoryCommandPayload;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.CompareArgument;
import io.github.lumi.network.CompareResultPayload;
import io.github.lumi.network.MergeArgument;
import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.network.OperationCancelPayload;
import io.github.lumi.network.PartialRestoreArgument;
import io.github.lumi.network.SaveArgument;
import io.github.lumi.network.PackageInspectionPayload;
import io.github.lumi.network.ZoneCreateArgument;
import io.github.lumi.network.ZoneCompareArgument;
import io.github.lumi.network.ZoneRestoreArgument;
import io.github.lumi.network.ZoneSaveArgument;
import io.github.lumi.network.WorkspaceCreateArgument;
import io.github.lumi.network.WorkspaceSettingsArgument;
import io.github.lumi.network.VersionTagsArgument;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Thin client sender/receiver; all history decisions stay on the server. */
public final class LumiClientNetworking {
    private final ClientHistoryStore history;
    private final ClientCompareStore comparisons;
    private final Consumer<HistorySnapshotPayload> snapshotListener;
    private final Consumer<OperationEventPayload> eventListener;
    private final Consumer<PackageInspectionPayload> packageListener;

    public LumiClientNetworking(
            ClientHistoryStore history,
            ClientCompareStore comparisons,
            Consumer<HistorySnapshotPayload> snapshotListener,
            Consumer<OperationEventPayload> eventListener,
            Consumer<PackageInspectionPayload> packageListener) {
        this.history = Objects.requireNonNull(history, "history");
        this.comparisons = Objects.requireNonNull(comparisons, "comparisons");
        this.snapshotListener = Objects.requireNonNull(snapshotListener, "snapshotListener");
        this.eventListener = Objects.requireNonNull(eventListener, "eventListener");
        this.packageListener = Objects.requireNonNull(packageListener, "packageListener");
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
                        context.client().execute(() -> comparisons.accept(payload)));
        ClientPlayNetworking.registerGlobalReceiver(
                PackageInspectionPayload.TYPE, (payload, context) ->
                        context.client().execute(() -> packageListener.accept(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            history.clear();
            comparisons.clear();
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

    public UUID restoreArea(CommitId target, BlockAreaTarget area) {
        return send(HistoryCommandPayload.Kind.RESTORE_AREA,
                new PartialRestoreArgument(
                        Objects.requireNonNull(target, "target"),
                        Objects.requireNonNull(area, "area")).encode());
    }

    public UUID quickRollback() {
        return send(HistoryCommandPayload.Kind.QUICK_ROLLBACK, "");
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

    public UUID createZone(String name, BlockBox area) {
        return send(HistoryCommandPayload.Kind.ZONE_CREATE,
                new ZoneCreateArgument(
                        Objects.requireNonNull(name, "name"),
                        Objects.requireNonNull(area, "area")).encode());
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
        return send(HistoryCommandPayload.Kind.ZONE_SAVE,
                new ZoneSaveArgument(
                        Objects.requireNonNull(zoneId, "zoneId"),
                        Objects.requireNonNull(message, "message")).encode());
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

    public UUID updateVersionTags(CommitId target, VersionTags tags) {
        return send(HistoryCommandPayload.Kind.UPDATE_VERSION_TAGS,
                new VersionTagsArgument(
                        Objects.requireNonNull(target, "target"),
                        Objects.requireNonNull(tags, "tags")).encode());
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
