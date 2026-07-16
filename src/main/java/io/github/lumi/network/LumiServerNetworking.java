package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
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
import java.util.concurrent.ConcurrentHashMap;
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
        ServerPlayNetworking.registerGlobalReceiver(
                HistoryCommandPayload.TYPE, LumiServerNetworking::receive);
        ServerPlayNetworking.registerGlobalReceiver(
                OperationCancelPayload.TYPE, LumiServerNetworking::cancel);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                LumiMod.serverRuntime().find(handler.getPlayer().level()).ifPresent(runtime ->
                        sendSnapshot(handler.getPlayer(), runtime)));
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
            Started started = start(player, runtime, actual, payload);
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
        } catch (IOException | IllegalArgumentException | IllegalStateException failed) {
            reject(player, payload, runtime, failed.getMessage());
        }
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
            case RESTORE -> runtime.startRestore(
                    new CommitId(new ObjectId(payload.argument())), author, terminal);
            case RESTORE_NO_ENTITIES -> runtime.startRestore(
                    new CommitId(new ObjectId(payload.argument())), author, false, terminal);
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
            case BRANCH_CREATE -> throw new IllegalStateException(
                    "Branch creation does not use the mutation queue");
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
            send(player, new HistorySnapshotPayload(
                    dimension(runtime), head.commit(), head.revision(),
                    runtime.mutations().snapshot().generations().size(),
                    runtime.operations().hasActiveOperation(),
                    runtime.recoveryJournal().isPresent(),
                    workspace.id(), workspace.name(), head.name().value(),
                    versions, branchViews));
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
}
