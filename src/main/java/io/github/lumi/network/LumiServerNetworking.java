package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.PackageName;
import io.github.lumi.domain.service.SaveRequest;
import io.github.lumi.domain.service.PermissionDecision;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.domain.service.RecoveryChoice;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.operation.OperationTicket;
import io.github.lumi.minecraft.operation.OperationProgress;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

/** Registers and dispatches the server-authoritative V2 play protocol. */
public final class LumiServerNetworking {
    private static final ConcurrentHashMap<UUID, TicketOwner> TICKET_OWNERS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ServerBossEvent> BOSS_BARS =
            new ConcurrentHashMap<>();
    private static final CompareCommandHandler COMPARES = new CompareCommandHandler(
            LumiServerNetworking::failureMessage, LumiServerNetworking::send);
    private static final ConcurrentHashMap<UUID, PendingPackage> PACKAGE_INSPECTIONS =
            new ConcurrentHashMap<>();
    private static final HistorySnapshotFactory SNAPSHOTS = new HistorySnapshotFactory();

    private LumiServerNetworking() { }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(
                HistoryCommandPayload.TYPE, HistoryCommandPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(
                OperationCancelPayload.TYPE, OperationCancelPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(
                HistorySnapshotPayload.TYPE, HistorySnapshotPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(
                OperationEventPayload.TYPE, OperationEventPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompareResultPayload.TYPE, CompareResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(
                PackageInspectionPayload.TYPE, PackageInspectionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(
                HistoryCommandPayload.TYPE, LumiServerNetworking::receive);
        ServerPlayNetworking.registerGlobalReceiver(
                OperationCancelPayload.TYPE, LumiServerNetworking::cancel);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                LumiMod.serverRuntime().find(handler.getPlayer().level()).ifPresent(runtime ->
                        sendSnapshot(handler.getPlayer(), runtime)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            cleanupPlayer(handler.getPlayer().getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(ignored -> clearState());
    }

    private static void receive(
            HistoryCommandPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        FabricDimensionRuntime runtime = LumiMod.serverRuntime()
                .find(player.level()).orElse(null);
        if (runtime == null) {
            reject(player, payload, null, "Lumi is not ready for this dimension");
            return;
        }
        LumiMod.LOGGER.info(
                "Lumi request received: id={}, action={}, player={}, dimension={}, revision={}",
                payload.requestId(), payload.kind(), player.getUUID(),
                dimension(runtime), payload.expectedRevision());
        try {
            PermissionDecision permission = LumiMod.serverRuntime().permission(player);
            if (permission != PermissionDecision.ALLOWED) {
                reject(player, payload, runtime, permissionMessage(permission));
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.SNAPSHOT_REFRESH) {
                sendSnapshot(player, runtime);
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.COMPARE_CANCEL) {
                COMPARES.cancelOwned(
                        UUID.fromString(payload.argument()), player.getUUID());
                return;
            }
            BranchRef actual = runtime.activeRef();
            if (!actual.commit().equals(payload.expectedCommit())
                    || actual.revision() != payload.expectedRevision()) {
                reject(player, payload, runtime, "History changed; refresh and try again");
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.BRANCH_CREATE) {
                runtime.createBranch(new BranchName(payload.argument().trim()));
                sendEvent(player, payload, runtime,
                        OperationEventPayload.State.SUCCEEDED, "Branch created");
                broadcastSnapshot(runtime);
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.WORKSPACE_CREATE) {
                WorkspaceCreateArgument argument =
                        WorkspaceCreateArgument.parse(payload.argument());
                runtime.createWorkspace(
                        argument.name(), argument.bounds(),
                        new CommitAuthor(player.getUUID(), player.getName().getString()));
                sendEvent(player, payload, runtime,
                        OperationEventPayload.State.SUCCEEDED, "Workspace created");
                broadcastSnapshot(runtime);
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.WORKSPACE_SETTINGS) {
                runtime.updateWorkspaceSettings(
                        WorkspaceSettingsArgument.parse(payload.argument()).settings());
                sendEvent(player, payload, runtime,
                        OperationEventPayload.State.SUCCEEDED, "Workspace settings updated");
                broadcastSnapshot(runtime);
                return;
            }
            if (isPackageCommand(payload.kind())) {
                packageCommand(player, runtime, actual, payload, context);
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.DELETE_VERSION) {
                runtime.softDelete(
                        new CommitId(new ObjectId(payload.argument())),
                        new CommitAuthor(player.getUUID(), player.getName().getString()));
                sendEvent(player, payload, runtime,
                        OperationEventPayload.State.SUCCEEDED, "Version deleted");
                broadcastSnapshot(runtime);
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.CLEANUP_VERSION) {
                runtime.cleanupTombstone(
                        new CommitId(new ObjectId(payload.argument())));
                sendEvent(player, payload, runtime,
                        OperationEventPayload.State.SUCCEEDED,
                        "Deleted version released for cleanup");
                broadcastSnapshot(runtime);
                return;
            }
            if (isZoneMetadata(payload.kind())) {
                updateZoneMetadata(player, runtime, payload);
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.COMPARE
                    || payload.kind() == HistoryCommandPayload.Kind.ZONE_COMPARE) {
                COMPARES.start(player, runtime, payload, context);
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.MERGE) {
                merge(player, runtime, payload, context);
                return;
            }
            track(player, runtime, payload, start(player, runtime, actual, payload));
        } catch (IOException | IllegalArgumentException | IllegalStateException failed) {
            reject(player, payload, runtime, failed.getMessage());
        }
    }

    private static boolean isZoneMetadata(HistoryCommandPayload.Kind kind) {
        return kind == HistoryCommandPayload.Kind.ZONE_CREATE
                || kind == HistoryCommandPayload.Kind.ZONE_ENTER
                || kind == HistoryCommandPayload.Kind.ZONE_LEAVE;
    }

    private static boolean isPackageCommand(HistoryCommandPayload.Kind kind) {
        return kind == HistoryCommandPayload.Kind.PACKAGE_EXPORT
                || kind == HistoryCommandPayload.Kind.PACKAGE_INSPECT
                || kind == HistoryCommandPayload.Kind.PACKAGE_IMPORT;
    }

    private static void packageCommand(
            ServerPlayer player,
            FabricDimensionRuntime runtime,
            BranchRef expected,
            HistoryCommandPayload payload,
            ServerPlayNetworking.Context context) {
        if (payload.kind() == HistoryCommandPayload.Kind.PACKAGE_IMPORT) {
            importPackage(player, runtime, expected, payload, context);
            return;
        }
        PackageName name = new PackageName(payload.argument());
        sendEvent(player, payload, runtime,
                OperationEventPayload.State.ACCEPTED,
                payload.kind() == HistoryCommandPayload.Kind.PACKAGE_EXPORT
                        ? "Exporting package" : "Inspecting package");
        var future = payload.kind() == HistoryCommandPayload.Kind.PACKAGE_EXPORT
                ? runtime.exportPackage(name, expected)
                : runtime.inspectPackage(name);
        future.whenComplete((inspection, failure) -> context.server().execute(() -> {
            if (failure != null) {
                reject(player, payload, runtime, failureMessage(failure));
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.PACKAGE_EXPORT) {
                sendEvent(player, payload, runtime,
                        OperationEventPayload.State.SUCCEEDED,
                        "Package exported: " + name + ".lumi");
                return;
            }
            if (context.server().getPlayerList().getPlayer(player.getUUID()) != player) {
                return;
            }
            PACKAGE_INSPECTIONS.put(player.getUUID(), new PendingPackage(
                    payload.requestId(), dimension(runtime), name, inspection));
            send(player, new PackageInspectionPayload(
                    payload.requestId(), dimension(runtime), name,
                    inspection.manifest().commit(),
                    displayText(inspection.source().message()),
                    displayText(inspection.source().author().name()),
                    inspection.manifest().totalBytes(),
                    inspection.manifest().objects().size()));
            sendEvent(player, payload, runtime,
                    OperationEventPayload.State.SUCCEEDED,
                    "Package inspected");
        }));
    }

    private static void importPackage(
            ServerPlayer player,
            FabricDimensionRuntime runtime,
            BranchRef expected,
            HistoryCommandPayload payload,
            ServerPlayNetworking.Context context) {
        PendingPackage pending = PACKAGE_INSPECTIONS.get(player.getUUID());
        UUID token = UUID.fromString(payload.argument());
        if (pending == null || !pending.token().equals(token)
                || !pending.dimensionId().equals(dimension(runtime))) {
            reject(player, payload, runtime,
                    "Inspect this package again before importing it");
            return;
        }
        PACKAGE_INSPECTIONS.remove(player.getUUID(), pending);
        sendEvent(player, payload, runtime,
                OperationEventPayload.State.ACCEPTED, "Importing package");
        CommitAuthor author = new CommitAuthor(
                player.getUUID(), player.getName().getString());
        runtime.importPackage(
                        pending.name(), pending.inspection(), expected, author)
                .whenComplete((result, failure) -> context.server().execute(() -> {
                    if (failure != null) {
                        reject(player, payload, runtime, failureMessage(failure));
                        return;
                    }
                    sendEvent(player, payload, runtime,
                            OperationEventPayload.State.SUCCEEDED,
                            "Package imported as branch "
                                    + result.branch().name().value());
                    broadcastSnapshot(runtime);
                }));
    }

    private static String displayText(String value) {
        int end = Math.min(value.length(), 4096);
        if (end > 0 && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static void updateZoneMetadata(
            ServerPlayer player,
            FabricDimensionRuntime runtime,
            HistoryCommandPayload payload) throws IOException {
        String message;
        if (payload.kind() == HistoryCommandPayload.Kind.ZONE_CREATE) {
            ZoneCreateArgument argument = ZoneCreateArgument.parse(payload.argument());
            runtime.createZone(argument.name(), 0xff4aa3ff,
                    argument.area().sectionCells(1_000_000));
            message = "Zone created";
        } else {
            UUID zoneId = UUID.fromString(payload.argument());
            boolean active = payload.kind() == HistoryCommandPayload.Kind.ZONE_ENTER;
            runtime.setZoneActorActive(zoneId, player.getUUID(), active);
            message = active ? "Zone entered" : "Zone left";
        }
        sendEvent(player, payload, runtime,
                OperationEventPayload.State.SUCCEEDED, message);
        broadcastSnapshot(runtime);
    }

    private static void merge(
            ServerPlayer player,
            FabricDimensionRuntime runtime,
            HistoryCommandPayload payload,
            ServerPlayNetworking.Context context) throws IOException {
        MergeArgument argument = MergeArgument.parse(payload.argument());
        CommitAuthor author = new CommitAuthor(
                player.getUUID(), player.getName().getString());
        sendEvent(player, payload, runtime,
                OperationEventPayload.State.ACCEPTED, "Preparing merge");
        runtime.prepareMerge(
                        new BranchName(argument.sourceBranch()), author, argument.message())
                .whenComplete((plan, failure) -> context.server().execute(() -> {
                    if (failure != null) {
                        reject(player, payload, runtime, failureMessage(failure));
                        return;
                    }
                    try {
                        DimensionMutation operation = runtime.startMerge(
                                plan, completed ->
                                        terminal(player, runtime, payload, completed));
                        OperationTicket ticket = runtime.operations().ticketOf(operation)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Accepted merge has no queue ticket"));
                        track(player, runtime, payload, new Started(ticket));
                    } catch (IOException | IllegalArgumentException | IllegalStateException failed) {
                        reject(player, payload, runtime, failed.getMessage());
                    }
                }));
    }

    private static void track(
            ServerPlayer player,
            FabricDimensionRuntime runtime,
            HistoryCommandPayload payload,
            Started started) {
        TICKET_OWNERS.put(started.ticket().id(),
                new TicketOwner(player.getUUID(), payload.requestId()));
        LumiMod.LOGGER.info(
                "Lumi request {} received operation ticket {}",
                payload.requestId(), started.ticket().id());
        runtime.operations().observeQueuePosition(started.ticket(), position -> {
            String message = position == 0
                    ? "Operation accepted" : "Queued at position " + position;
            sendEvent(player, payload, runtime, OperationEventPayload.State.ACCEPTED,
                    message, Optional.of(started.ticket()), position);
            LumiMod.LOGGER.info(
                    "Lumi request {} ticket {} queue position {}",
                    payload.requestId(), started.ticket().id(), position);
            bossBar(player, started.ticket(), position).setVisible(position == 0);
        });
        AtomicReference<String> loggedPhase = new AtomicReference<>("");
        runtime.operations().observeProgress(started.ticket(), progress -> {
            if (!progress.phase().equals(loggedPhase.getAndSet(progress.phase()))) {
                LumiMod.LOGGER.info(
                        "Lumi request {} ticket {} entered phase '{}' ({}/{})",
                        payload.requestId(), started.ticket().id(), progress.phase(),
                        progress.completed(), progress.total());
            }
            runtime.operations().queuePosition(started.ticket()).ifPresent(position ->
                        sendProgress(player, payload, runtime, started.ticket(),
                                position, progress));
        });
        broadcastSnapshot(runtime);
    }

    private static void cleanupPlayer(UUID playerId) {
        COMPARES.cleanupPlayer(playerId);
        PACKAGE_INSPECTIONS.remove(playerId);
        TICKET_OWNERS.forEach((ticketId, owner) -> {
            if (owner.playerId().equals(playerId)
                    && TICKET_OWNERS.remove(ticketId, owner)) {
                removeBossBar(ticketId);
            }
        });
    }

    private static void clearState() {
        COMPARES.clear();
        BOSS_BARS.keySet().forEach(LumiServerNetworking::removeBossBar);
        TICKET_OWNERS.clear();
        PACKAGE_INSPECTIONS.clear();
    }

    private static String failureMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "Operation failed" : message;
    }

    private static Started start(
            ServerPlayer player,
            FabricDimensionRuntime runtime,
            BranchRef expected,
            HistoryCommandPayload payload) throws IOException {
        CommitAuthor author = new CommitAuthor(player.getUUID(), player.getName().getString());
        java.util.function.Consumer<DimensionMutation> terminal = operation ->
                terminal(player, runtime, payload, operation);
        DimensionMutation operation = switch (payload.kind()) {
            case SAVE -> {
                SaveArgument save = SaveArgument.parse(payload.argument());
                yield runtime.startSave(new SaveRequest(
                        expected, author, save.message(), Instant.now(),
                        runtime.activeWorkspaceId(), Optional.empty(),
                        CommitKind.MANUAL, save.tags()), terminal);
            }
            case AMEND -> {
                SaveArgument save = SaveArgument.parse(payload.argument());
                yield runtime.startSave(new SaveRequest(
                        expected, author, save.message(), Instant.now(),
                        runtime.activeWorkspaceId(), Optional.empty(),
                        CommitKind.AMEND, save.tags()), terminal);
            }
            case RESTORE -> runtime.startRestore(
                    new CommitId(new ObjectId(payload.argument())), author, terminal);
            case RESTORE_NO_ENTITIES -> runtime.startRestore(
                    new CommitId(new ObjectId(payload.argument())), author, false, terminal);
            case RESTORE_AREA -> {
                PartialRestoreArgument partial = PartialRestoreArgument.parse(payload.argument());
                yield runtime.startPartialRestore(
                        partial.target(), partial.area(), author, terminal);
            }
            case QUICK_ROLLBACK -> runtime.startQuickRollback(author, terminal);
            case UNDO -> runtime.startLiveAction(
                    player.getUUID(), LiveActionJournal.Direction.UNDO, terminal);
            case REDO -> runtime.startLiveAction(
                    player.getUUID(), LiveActionJournal.Direction.REDO, terminal);
            case BRANCH_SWITCH -> runtime.startBranchSwitch(
                    new BranchName(payload.argument()), terminal);
            case WORKSPACE_SWITCH -> runtime.startWorkspaceSwitch(
                    UUID.fromString(payload.argument()), terminal);
            case RECOVER_RESUME -> runtime.startRecovery(
                    RecoveryChoice.RESUME_TARGET, terminal);
            case RECOVER_RETURN -> runtime.startRecovery(
                    RecoveryChoice.RETURN_CHECKPOINT, terminal);
            case ZONE_SAVE -> {
                ZoneSaveArgument zone = ZoneSaveArgument.parse(payload.argument());
                yield runtime.startZoneSave(
                        expected, author, player.getUUID(), zone.zoneId(),
                        zone.message(), terminal);
            }
            case ZONE_RESTORE -> {
                ZoneRestoreArgument zone = ZoneRestoreArgument.parse(payload.argument());
                yield runtime.startZoneRestore(
                        zone.target(), zone.zoneId(), author, terminal);
            }
            case BRANCH_CREATE -> throw new IllegalStateException(
                    "Branch creation does not use the mutation queue");
            case COMPARE, ZONE_COMPARE -> throw new IllegalStateException(
                    "Compare does not use the mutation queue");
            case COMPARE_CANCEL -> throw new IllegalStateException(
                    "Compare cancellation does not use the mutation queue");
            case MERGE -> throw new IllegalStateException(
                    "Merge preparation starts before the mutation queue");
            case ZONE_CREATE, ZONE_ENTER, ZONE_LEAVE -> throw new IllegalStateException(
                    "Zone metadata does not use the mutation queue");
            case DELETE_VERSION -> throw new IllegalStateException(
                    "Version deletion does not use the mutation queue");
            case CLEANUP_VERSION -> throw new IllegalStateException(
                    "Version cleanup does not use the mutation queue");
            case PACKAGE_EXPORT, PACKAGE_INSPECT, PACKAGE_IMPORT ->
                    throw new IllegalStateException(
                            "Package commands do not use the mutation queue");
            case WORKSPACE_CREATE -> throw new IllegalStateException(
                    "Workspace creation does not use the mutation queue");
            case WORKSPACE_SETTINGS -> throw new IllegalStateException(
                    "Workspace settings do not use the mutation queue");
            case SNAPSHOT_REFRESH -> throw new IllegalStateException(
                    "Snapshot refresh does not use the mutation queue");
        };
        OperationTicket ticket = runtime.operations().ticketOf(operation).orElseThrow(
                () -> new IllegalStateException("Accepted operation has no queue ticket"));
        return new Started(ticket);
    }

    private static void terminal(
            ServerPlayer player,
            FabricDimensionRuntime runtime,
            HistoryCommandPayload payload,
            DimensionMutation operation) {
        TICKET_OWNERS.entrySet().removeIf(entry -> {
            if (!entry.getValue().requestId().equals(payload.requestId())) {
                return false;
            }
            removeBossBar(entry.getKey());
            return true;
        });
        OperationEventPayload.State state = eventState(operation.terminalState());
        String message = operation.failure().map(Throwable::getMessage)
                .filter(value -> value != null && !value.isBlank())
                .orElseGet(() -> terminalMessage(operation.terminalState()));
        sendEvent(player, payload, runtime, state, message);
        broadcastSnapshot(runtime);
    }

    private static void cancel(
            OperationCancelPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        FabricDimensionRuntime runtime = LumiMod.serverRuntime()
                .find(player.level()).orElse(null);
        if (runtime == null) {
            return;
        }
        try {
            if (LumiMod.serverRuntime().permission(player) != PermissionDecision.ALLOWED) {
                return;
            }
        } catch (IOException unavailable) {
            LumiMod.LOGGER.error("Cannot read Lumi permissions", unavailable);
            return;
        }
        TicketOwner owner = TICKET_OWNERS.get(payload.ticketId());
        if (owner == null || !owner.playerId().equals(player.getUUID())) {
            sendEvent(player, payload.requestId(), runtime, OperationEventPayload.State.FAILED,
                    "Operation ticket is not owned by this player");
            return;
        }
        OperationTicket ticket = new OperationTicket(payload.ticketId());
        OptionalInt position = runtime.operations().queuePosition(ticket);
        boolean active = position.isPresent() && position.orElseThrow() == 0;
        try {
            if (!runtime.operations().cancel(ticket)) {
                sendEvent(player, payload.requestId(), runtime,
                        OperationEventPayload.State.FAILED,
                        "Operation can no longer be cancelled safely");
                return;
            }
            LumiMod.LOGGER.info(
                    "Lumi cancellation accepted: request={}, ticket={}, active={}",
                    payload.requestId(), payload.ticketId(), active);
        } catch (IOException failed) {
            sendEvent(player, payload.requestId(), runtime, OperationEventPayload.State.FAILED,
                    failureMessage(failed));
            return;
        }
        if (active) {
            return;
        }
        TICKET_OWNERS.remove(payload.ticketId(), owner);
        removeBossBar(payload.ticketId());
        sendEvent(player, owner.requestId(), runtime, OperationEventPayload.State.CANCELLED,
                "Operation cancelled before it started");
        broadcastSnapshot(runtime);
    }

    private static OperationEventPayload.State eventState(MutationTerminalState state) {
        return switch (state) {
            case SUCCEEDED -> OperationEventPayload.State.SUCCEEDED;
            case FAILED -> OperationEventPayload.State.FAILED;
            case CANCELLED -> OperationEventPayload.State.CANCELLED;
            case RETURNED -> OperationEventPayload.State.RETURNED;
            case DEGRADED -> OperationEventPayload.State.DEGRADED;
        };
    }

    private static String terminalMessage(MutationTerminalState state) {
        return switch (state) {
            case SUCCEEDED -> "Operation completed";
            case FAILED -> "Operation failed";
            case CANCELLED -> "Operation cancelled";
            case RETURNED -> "Target failed verification; returned safely";
            case DEGRADED -> "Dimension degraded; recovery is required";
        };
    }

    private static String permissionMessage(PermissionDecision decision) {
        return switch (decision) {
            case ALLOWED -> "Operation allowed";
            case OPERATOR_REQUIRED -> "Lumi requires operator permission";
            case SURVIVAL_OPT_IN_REQUIRED -> "Enable Lumi in Survival before using it";
        };
    }

    private static void reject(
            ServerPlayer player,
            HistoryCommandPayload payload,
            FabricDimensionRuntime runtime,
            String message) {
        if (runtime == null) {
            LumiMod.LOGGER.warn("Rejected Lumi request {}: {}", payload.requestId(), message);
            return;
        }
        sendEvent(player, payload, runtime, OperationEventPayload.State.FAILED, message);
        sendSnapshot(player, runtime);
    }

    private static void sendEvent(
            ServerPlayer player,
            HistoryCommandPayload request,
            FabricDimensionRuntime runtime,
            OperationEventPayload.State state,
            String message) {
        sendEvent(player, request.requestId(), runtime, state, message, Optional.empty(), -1);
    }

    private static void sendEvent(
            ServerPlayer player,
            HistoryCommandPayload request,
            FabricDimensionRuntime runtime,
            OperationEventPayload.State state,
            String message,
            Optional<OperationTicket> ticket,
            int queuePosition) {
        sendEvent(player, request.requestId(), runtime, state, message, ticket, queuePosition);
    }

    private static void sendEvent(
            ServerPlayer player,
            UUID requestId,
            FabricDimensionRuntime runtime,
            OperationEventPayload.State state,
            String message) {
        sendEvent(player, requestId, runtime, state, message, Optional.empty(), -1);
    }

    private static void sendEvent(
            ServerPlayer player,
            UUID requestId,
            FabricDimensionRuntime runtime,
            OperationEventPayload.State state,
            String message,
            Optional<OperationTicket> ticket,
            int queuePosition) {
        try {
            BranchRef head = runtime.activeRef();
            send(player, new OperationEventPayload(
                    requestId, dimension(runtime), state,
                    message == null ? "Operation failed" : message,
                    head.commit(), head.revision(), ticket.map(OperationTicket::id),
                    queuePosition));
            if (state != OperationEventPayload.State.ACCEPTED
                    && state != OperationEventPayload.State.PROGRESS) {
                LumiMod.LOGGER.info(
                        "Lumi request finished: id={}, state={}, player={}, dimension={}",
                        requestId, state, player.getUUID(), dimension(runtime));
            }
        } catch (IOException failed) {
            LumiMod.LOGGER.error("Cannot publish Lumi operation event", failed);
        }
    }

    private static void sendProgress(
            ServerPlayer player,
            HistoryCommandPayload request,
            FabricDimensionRuntime runtime,
            OperationTicket ticket,
            int queuePosition,
            OperationProgress progress) {
        try {
            BranchRef head = runtime.activeRef();
            send(player, new OperationEventPayload(
                    request.requestId(), dimension(runtime),
                    OperationEventPayload.State.PROGRESS, progress.phase(),
                    head.commit(), head.revision(), Optional.of(ticket.id()),
                    queuePosition, Optional.of(progress)));
            ServerBossEvent boss = bossBar(player, ticket, queuePosition);
            boss.setName(Component.literal(progress.phase()));
            boss.setProgress((float) progress.fraction().orElse(0.0));
            boss.setVisible(queuePosition == 0);
        } catch (IOException failed) {
            LumiMod.LOGGER.error("Cannot publish Lumi operation progress", failed);
        }
    }

    private static ServerBossEvent bossBar(
            ServerPlayer player, OperationTicket ticket, int queuePosition) {
        ServerBossEvent boss = BOSS_BARS.computeIfAbsent(ticket.id(), ignored -> {
            var created = new ServerBossEvent(
                    Component.literal("Lumi"), BossEvent.BossBarColor.BLUE,
                    BossEvent.BossBarOverlay.PROGRESS);
            created.addPlayer(player);
            return created;
        });
        boss.setVisible(queuePosition == 0);
        return boss;
    }

    private static void removeBossBar(UUID ticketId) {
        ServerBossEvent boss = BOSS_BARS.remove(ticketId);
        if (boss != null) {
            boss.removeAllPlayers();
        }
    }

    private static void sendSnapshot(ServerPlayer player, FabricDimensionRuntime runtime) {
        try {
            send(player, SNAPSHOTS.create(player, runtime));
        } catch (IOException failed) {
            LumiMod.LOGGER.error("Cannot publish Lumi history snapshot", failed);
        }
    }

    private static void broadcastSnapshot(FabricDimensionRuntime runtime) {
        for (ServerPlayer player : runtime.level().players()) {
            sendSnapshot(player, runtime);
        }
    }

    private static void send(
            ServerPlayer player,
            net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.type())) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static String dimension(FabricDimensionRuntime runtime) {
        return runtime.level().dimension().identifier().toString();
    }

    private record Started(OperationTicket ticket) { }
    private record TicketOwner(UUID playerId, UUID requestId) { }
    private record PendingPackage(
            UUID token,
            String dimensionId,
            PackageName name,
            io.github.lumi.domain.service.ImportExportService.PackageInspection inspection) { }
}
