package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiClient;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.network.OperationEventPayload;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.world.level.GameType;

/** Deterministic TCP conflicts and disconnects while a dimension slot is occupied. */
final class LumiMultiplayerQueueScenario {
    private final ClientGameTestContext context;
    private final TestServerContext server;
    private final LumiBehaviorReport report;
    private final LumiMultiplayerMailbox mailbox;

    LumiMultiplayerQueueScenario(ClientGameTestContext context, TestServerContext server,
            LumiBehaviorReport report, LumiMultiplayerMailbox mailbox) {
        this.context = context;
        this.server = server;
        this.report = report;
        this.mailbox = mailbox;
    }

    void run() throws IOException {
        conflictingSaves();
        disconnectedQueuedSave();
        revokedQueuedAuthority();
    }

    private void conflictingSaves() throws IOException {
        new LumiDenseSectionFixture(context, server, report).fill("multiplayer-conflict-fixture",
                new io.github.lumi.domain.model.BlockBox(0, 0, 0, 15, 0, 15), 1);
        synchronizeHeads();
        try (var hold = holdQueue()) {
            String peerRequest = mailbox.request(context, "save", "peer-conflict").getProperty("request");
            UUID ownerRequest = context.computeOnClient(client ->
                    LumiClient.networking().save("owner-conflict"));
            await(() -> queueSize() == 2, "Both conflicting requests were not queued");
            hold.close();
            OperationEventPayload owner = awaitTerminal(ownerRequest);
            String peerState = mailbox.request(context, "await", peerRequest).getProperty("state");
            boolean ownerSucceeded = owner.state() == OperationEventPayload.State.SUCCEEDED;
            boolean peerSucceeded = peerState.equals("SUCCEEDED");
            if (ownerSucceeded == peerSucceeded
                    || (!ownerSucceeded && owner.state() != OperationEventPayload.State.FAILED)
                    || (!peerSucceeded && !peerState.equals("FAILED"))) {
                throw new AssertionError("Conflict outcomes: owner=" + owner.state() + ";peer=" + peerState);
            }
        }
        synchronizeHeads();
        report.event("gate", "multiplayer_stale_save", "succeeded", 0, 0,
                "queued=2;published=1;staleRejected=1");
    }

    private void disconnectedQueuedSave() throws IOException {
        new LumiDenseSectionFixture(context, server, report).fill("multiplayer-disconnect-fixture",
                new io.github.lumi.domain.model.BlockBox(0, 0, 0, 15, 0, 15), 2);
        String before = head();
        try (var hold = holdQueue()) {
            mailbox.request(context, "save", "disconnected-queued");
            await(() -> queueSize() == 1, "Disconnected request never entered the queue");
            mailbox.request(context, "disconnect", "");
            await(() -> players() == 1 && queueSize() == 0,
                    "Disconnect did not discard client-owned queued work");
        }
        awaitIdle();
        if (!head().equals(before)) throw new AssertionError("Disconnected queued Save published");
        mailbox.request(context, "connect", "");
        await(() -> players() == 2, "Peer did not reconnect");
        synchronizeHeads();
        report.event("gate", "multiplayer_queued_disconnect", "succeeded", 0, 0, "");
    }

    private void revokedQueuedAuthority() throws IOException {
        String before = head();
        try (var hold = holdQueue()) {
            String request = mailbox.request(context, "save", "revoked-queued").getProperty("request");
            await(() -> queueSize() == 1, "Authority test did not queue");
            server.runOnServer(minecraft -> minecraft.getPlayerList().getPlayers().stream()
                    .filter(player -> player.getName().getString().equals("Lumi-peer"))
                    .findFirst().orElseThrow().setGameMode(GameType.SURVIVAL));
            hold.close();
            String outcome = mailbox.request(context, "await", request).getProperty("state");
            if (!outcome.equals("FAILED") || !head().equals(before)) {
                throw new AssertionError("Revoked actor published queued work: " + outcome);
            }
        } finally {
            server.runOnServer(minecraft -> minecraft.getPlayerList().getPlayers().stream()
                    .filter(player -> player.getName().getString().equals("Lumi-peer"))
                    .findFirst().orElseThrow().setGameMode(GameType.CREATIVE));
        }
        synchronizeHeads();
        report.event("gate", "multiplayer_activation_authority", "succeeded", 0, 0, "");
    }

    private QueueHold holdQueue() {
        var hold = new QueueHold();
        server.runOnServer(minecraft -> LumiMod.serverRuntime().find(minecraft.overworld())
                .orElseThrow().operations().start(hold));
        return hold;
    }

    private void synchronizeHeads() throws IOException {
        awaitIdle();
        String expected = head();
        context.runOnClient(client -> LumiClient.networking().refreshSnapshot());
        context.waitFor(client -> LumiClient.history().state().snapshot()
                .filter(snapshot -> snapshot.head().hex().equals(expected)).isPresent(), 12000);
        mailbox.request(context, "head", expected);
    }

    private OperationEventPayload awaitTerminal(UUID request) {
        context.waitFor(client -> {
            var event = LumiClient.history().state().events().get(request);
            return event != null && event.state() != OperationEventPayload.State.ACCEPTED
                    && event.state() != OperationEventPayload.State.PROGRESS;
        }, 12000);
        return context.computeOnClient(client -> LumiClient.history().state().events().get(request));
    }

    private void awaitIdle() {
        await(() -> server.computeOnServer(minecraft -> {
            var runtime = LumiMod.serverRuntime().find(minecraft.overworld()).orElseThrow();
            return !runtime.operations().hasActiveOperation() && runtime.operations().queuedCount() == 0
                    && !runtime.freeze().isFrozen() && runtime.recoveryJournal().isEmpty();
        }), "Multiplayer operation did not settle");
    }

    private int queueSize() {
        return server.computeOnServer(minecraft -> LumiMod.serverRuntime().find(minecraft.overworld())
                .orElseThrow().operations().queuedCount());
    }

    private String head() throws IOException {
        return server.computeOnServer(minecraft -> LumiMod.serverRuntime().find(minecraft.overworld())
                .orElseThrow().activeRef().commit().hex());
    }

    private int players() {
        return server.computeOnServer(minecraft -> minecraft.getPlayerList().getPlayerCount());
    }

    private void await(BooleanSupplier condition, String failure) {
        for (int tick = 0; tick < 12000; tick++) {
            if (condition.getAsBoolean()) return;
            context.waitTick();
        }
        throw new AssertionError(failure);
    }

    private static final class QueueHold implements DimensionMutation {
        private final AtomicBoolean released = new AtomicBoolean();
        @Override public void advance(long deadlineNanos) { }
        @Override public boolean isTerminal() { return released.get(); }
        @Override public boolean isSafeToRelease() { return true; }
        @Override public void close() { released.set(true); }
    }
}
