package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ComparisonSummary;
import io.github.lumi.domain.model.ObjectId;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
    private static final ConcurrentHashMap<UUID, CompareJob> COMPARES =
            new ConcurrentHashMap<>();

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
        ServerPlayNetworking.registerGlobalReceiver(
                HistoryCommandPayload.TYPE, LumiServerNetworking::receive);
        ServerPlayNetworking.registerGlobalReceiver(
                OperationCancelPayload.TYPE, LumiServerNetworking::cancel);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                LumiMod.serverRuntime().find(handler.getPlayer().level()).ifPresent(runtime ->
                        sendSnapshot(handler.getPlayer(), runtime)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                cancelCompares(handler.getPlayer().getUUID()));
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
        try {
            PermissionDecision permission = LumiMod.serverRuntime().permission(player);
            if (permission != PermissionDecision.ALLOWED) {
                reject(player, payload, runtime, permissionMessage(permission));
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.COMPARE_CANCEL) {
                cancelCompare(player, payload);
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
                sendSnapshot(player, runtime);
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.DELETE_VERSION) {
                runtime.softDelete(
                        new CommitId(new ObjectId(payload.argument())),
                        new CommitAuthor(player.getUUID(), player.getName().getString()));
                sendEvent(player, payload, runtime,
                        OperationEventPayload.State.SUCCEEDED, "Version deleted");
                sendSnapshot(player, runtime);
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.CLEANUP_VERSION) {
                runtime.cleanupTombstone(
                        new CommitId(new ObjectId(payload.argument())));
                sendEvent(player, payload, runtime,
                        OperationEventPayload.State.SUCCEEDED,
                        "Deleted version released for cleanup");
                sendSnapshot(player, runtime);
                return;
            }
            if (isZoneMetadata(payload.kind())) {
                updateZoneMetadata(player, runtime, payload);
                return;
            }
            if (payload.kind() == HistoryCommandPayload.Kind.COMPARE) {
                compare(player, runtime, payload, context);
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
        sendSnapshot(player, runtime);
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
        runtime.operations().observeQueuePosition(started.ticket(), position -> {
            String message = position == 0
                    ? "Operation accepted" : "Queued at position " + position;
            sendEvent(player, payload, runtime, OperationEventPayload.State.ACCEPTED,
                    message, Optional.of(started.ticket()), position);
            bossBar(player, started.ticket(), position).setVisible(position == 0);
        });
        runtime.operations().observeProgress(started.ticket(), progress ->
                runtime.operations().queuePosition(started.ticket()).ifPresent(position ->
                        sendProgress(player, payload, runtime, started.ticket(),
                                position, progress)));
    }

    private static void compare(
            ServerPlayer player,
            FabricDimensionRuntime runtime,
            HistoryCommandPayload payload,
            ServerPlayNetworking.Context context) throws IOException {
        CompareArgument argument = CompareArgument.parse(payload.argument());
        String dimension = dimension(runtime);
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<ComparisonSummary> future = runtime.compare(
                argument.before(), argument.after(), cancelled::get);
        CompareJob job = new CompareJob(player.getUUID(), cancelled, future);
        if (COMPARES.putIfAbsent(payload.requestId(), job) != null) {
            cancelled.set(true);
            future.cancel(false);
            throw new IllegalStateException("Compare request already exists");
        }
        future
                .whenComplete((summary, failure) -> context.server().execute(() -> {
                    if (!COMPARES.remove(payload.requestId(), job) || cancelled.get()) {
                        return;
                    }
                    if (failure == null) {
                        send(player, success(payload.requestId(), dimension, summary));
                    } else {
                        send(player, new CompareResultPayload(
                                payload.requestId(), dimension,
                                argument.before(), argument.after(), 0, 0,
                                java.util.List.of(), failureMessage(failure)));
                    }
                }));
    }

    private static void cancelCompare(
            ServerPlayer player, HistoryCommandPayload payload) {
        UUID target = UUID.fromString(payload.argument());
        CompareJob job = COMPARES.get(target);
        if (job != null
                && job.playerId().equals(player.getUUID())
                && COMPARES.remove(target, job)) {
            job.cancelled().set(true);
            job.future().cancel(false);
        }
    }

    private static void cancelCompares(UUID playerId) {
        COMPARES.forEach((request, job) -> {
            if (job.playerId().equals(playerId) && COMPARES.remove(request, job)) {
                job.cancelled().set(true);
                job.future().cancel(false);
            }
        });
    }

    private static CompareResultPayload success(
            UUID requestId, String dimension, ComparisonSummary summary) {
        var materials = summary.materials().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .limit(128)
                .map(entry -> new CompareResultPayload.Material(
                        entry.getKey(), entry.getValue().before(), entry.getValue().after()))
                .toList();
        return new CompareResultPayload(
                requestId, dimension, summary.before(), summary.after(),
                summary.changedSections(), summary.changedEntityChunks(), materials, "");
    }

    private static String failureMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "Compare failed" : message;
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
            case SAVE -> runtime.startSave(new SaveRequest(
                    expected, author, payload.argument(), Instant.now(),
                    runtime.activeWorkspaceId(), Optional.empty(), CommitKind.MANUAL), terminal);
            case AMEND -> runtime.startSave(new SaveRequest(
                    expected, author, payload.argument(), Instant.now(),
                    runtime.activeWorkspaceId(), Optional.empty(), CommitKind.AMEND), terminal);
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
            case COMPARE -> throw new IllegalStateException(
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
        sendSnapshot(player, runtime);
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
        if (!runtime.operations().cancelQueued(new OperationTicket(payload.ticketId()))) {
            sendEvent(player, payload.requestId(), runtime, OperationEventPayload.State.FAILED,
                    "Active operations cannot be cancelled");
            return;
        }
        TICKET_OWNERS.remove(payload.ticketId(), owner);
        removeBossBar(payload.ticketId());
        sendEvent(player, owner.requestId(), runtime, OperationEventPayload.State.CANCELLED,
                "Queued operation cancelled");
        sendSnapshot(player, runtime);
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
            BranchRef head = runtime.activeRef();
            var workspace = runtime.activeWorkspace();
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
                            .collect(java.util.stream.Collectors.toSet()),
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
            send(player, new HistorySnapshotPayload(
                    dimension(runtime), head.commit(), head.revision(),
                    runtime.mutations().snapshot().generations().size(),
                    runtime.operations().hasActiveOperation(),
                    runtime.recoveryJournal().isPresent(),
                    workspace.id(), workspace.name(), head.name().value(),
                    versions, branchViews, zoneViews, deleted));
        } catch (IOException failed) {
            LumiMod.LOGGER.error("Cannot publish Lumi history snapshot", failed);
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
    private record CompareJob(
            UUID playerId,
            AtomicBoolean cancelled,
            CompletableFuture<ComparisonSummary> future) { }
}
