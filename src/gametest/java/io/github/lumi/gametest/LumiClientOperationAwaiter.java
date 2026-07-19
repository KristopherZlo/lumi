package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiClient;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.storage.repository.OperationJournalRepository;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.server.MinecraftServer;

/** Correlates one UI action with exactly one successful client operation event. */
final class LumiClientOperationAwaiter {
    private final ClientGameTestContext context;
    private final TestServerContext server;
    private final int timeoutTicks;

    LumiClientOperationAwaiter(
            ClientGameTestContext context, TestServerContext server, int timeoutTicks) {
        this.context = context;
        this.server = server;
        this.timeoutTicks = timeoutTicks;
    }

    Set<UUID> eventIds() {
        return Set.copyOf(context.computeOnClient(client ->
                LumiClient.history().state().events().keySet()));
    }

    OperationEventPayload awaitSuccess(Set<UUID> previousEvents, String name) {
        for (int tick = 0; tick < timeoutTicks; tick++) {
            List<OperationEventPayload> newEvents = context.computeOnClient(client ->
                    LumiClient.history().state().events().entrySet().stream()
                            .filter(entry -> !previousEvents.contains(entry.getKey()))
                            .map(java.util.Map.Entry::getValue).toList());
            require(newEvents.size() <= 1,
                    name + " emitted more than one request: " + newEvents.size());
            if (newEvents.size() == 1) {
                OperationEventPayload event = newEvents.getFirst();
                if (event.state() != OperationEventPayload.State.ACCEPTED
                        && event.state() != OperationEventPayload.State.PROGRESS) {
                    require(event.state() == OperationEventPayload.State.SUCCEEDED,
                            name + " ended as " + event.state()
                                    + ": " + event.message());
                    return event;
                }
            }
            context.waitTick();
        }
        throw new AssertionError(name + " did not settle within "
                + timeoutTicks + " ticks");
    }

    void awaitReleased(String name) throws IOException {
        ServerState serverState = null;
        ClientState clientState = null;
        int maximumTicks = Math.min(timeoutTicks, 200);
        for (int tick = 0; tick < maximumTicks; tick++) {
            serverState = serverState();
            clientState = clientState();
            if (serverState.released() && clientState.released()) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(name + " terminal event did not release operation state: "
                + serverState + ", " + clientState);
    }

    private ServerState serverState() throws IOException {
        return server.computeOnServer(minecraft -> {
            FabricDimensionRuntime runtime = runtime(minecraft);
            return new ServerState(
                    runtime.operations().hasActiveOperation(),
                    runtime.freeze().isFrozen(),
                    runtime.recoveryJournal().isPresent(),
                    new OperationJournalRepository(runtime.repository()).read().isPresent());
        });
    }

    private ClientState clientState() {
        return context.computeOnClient(client -> {
            var state = LumiClient.history().state();
            var snapshot = state.snapshot().orElse(null);
            List<UUID> activeEvents = state.events().values().stream()
                    .filter(event -> event.state() == OperationEventPayload.State.ACCEPTED
                            || event.state() == OperationEventPayload.State.PROGRESS)
                    .map(OperationEventPayload::requestId)
                    .toList();
            return new ClientState(
                    snapshot == null,
                    snapshot != null && snapshot.operationActive(),
                    snapshot != null && snapshot.recoveryPending(),
                    activeEvents);
        });
    }

    private static FabricDimensionRuntime runtime(MinecraftServer minecraft) {
        var level = minecraft.getPlayerList().getPlayers().getFirst().level();
        return LumiMod.serverRuntime().find(level).orElseThrow(
                () -> new AssertionError("Lumi runtime is not loaded"));
    }

    private record ServerState(
            boolean operationActive,
            boolean frozen,
            boolean recoveryPending,
            boolean journalPresent) {
        private boolean released() {
            return !operationActive && !frozen && !recoveryPending && !journalPresent;
        }
    }

    private record ClientState(
            boolean snapshotMissing,
            boolean operationActive,
            boolean recoveryPending,
            List<UUID> activeEvents) {
        private boolean released() {
            return !snapshotMissing && !operationActive && !recoveryPending;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
