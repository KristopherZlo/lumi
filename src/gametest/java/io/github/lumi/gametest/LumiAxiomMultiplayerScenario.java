package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiClient;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.network.OperationEventPayload;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Native split edits over two real TCP connections, including an old-dimension tail. */
final class LumiAxiomMultiplayerScenario {
    private static final BlockPos OWNER = new BlockPos(0, 170, 0);
    private static final BlockPos PEER = new BlockPos(0, 170, 1);
    private static final List<BlockBox> AREA = List.of(new BlockBox(0, 170, 0, 32, 170, 1));
    private final ClientGameTestContext context;
    private final TestServerContext server;
    private final LumiBehaviorReport report;
    private final LumiMultiplayerMailbox mailbox;
    private final LumiBehaviorOperations operations;
    private final UUID owner;
    private final UUID peer;

    LumiAxiomMultiplayerScenario(ClientGameTestContext context, TestServerContext server,
            LumiBehaviorReport report, LumiMultiplayerMailbox mailbox) {
        this.context = context;
        this.server = server;
        this.report = report;
        this.mailbox = mailbox;
        operations = new LumiBehaviorOperations(context, server, report);
        owner = context.computeOnClient(client -> client.player.getUUID());
        peer = server.computeOnServer(minecraft -> minecraft.getPlayerList().getPlayers().stream()
                .filter(player -> !player.getUUID().equals(owner)).findFirst().orElseThrow().getUUID());
    }

    void run() throws IOException {
        CommitId baseline = operations.activeCommit();
        var before = snapshot("axiom_tcp_before");
        var local = new LumiAxiomDispatchTestDriver(context);
        local.begin(OWNER, Blocks.GOLD_BLOCK.defaultBlockState());
        beginPeer(PEER);
        for (int packet = 1; packet <= 3; packet++) {
            local.tick();
            awaitPackets(OWNER, Blocks.GOLD_BLOCK, packet);
            tickPeer();
            awaitPackets(PEER, Blocks.EMERALD_BLOCK, packet);
            if (packet < 3) {
                assertArriving(owner);
                assertArriving(peer);
            }
        }
        awaitComplete();
        server.runOnServer(minecraft -> {
            for (UUID actor : List.of(owner, peer)) {
                if (LumiAxiomDispatchTestDriver.changedCells(runtime(minecraft).liveActions().prepareUndo(actor).orElseThrow()) != 3) {
                    throw new AssertionError("TCP dispatch was not one three-block action");
                }
            }
        });
        var after = snapshot("axiom_tcp_after");
        operations.undo("axiom_tcp_owner");
        peerAction("undo", true);
        snapshot("axiom_tcp_undo").assertMatches(before, "Two builders' native Undo was inexact");
        operations.redo("axiom_tcp_owner");
        peerAction("redo", true);
        snapshot("axiom_tcp_redo").assertMatches(after, "Two builders' native Redo was inexact");
        report.event("gate", "axiom_tcp_split_dispatch", "succeeded", 0, 0,
                "two actors; six interleaved native section packets; no partial Undo; exact Undo/Redo");

        reset(baseline, before);
        local.begin(OWNER, Blocks.GOLD_BLOCK.defaultBlockState());
        beginPeer(OWNER);
        for (int packet = 1; packet <= 3; packet++) {
            local.tick();
            // Wait for this position before the second connection overwrites it.
            int index = packet - 1;
            await(() -> server.computeOnServer(minecraft -> minecraft.overworld()
                    .getBlockState(OWNER.offset(index * 16, 0, 0)).is(Blocks.GOLD_BLOCK)),
                    "Owner overlap packet did not arrive");
            tickPeer();
            awaitPackets(OWNER, Blocks.EMERALD_BLOCK, packet);
        }
        awaitComplete();
        var conflicted = snapshot("axiom_tcp_overlap");
        UUID request = context.computeOnClient(client -> LumiClient.networking().undo());
        context.waitFor(client -> terminal(LumiClient.history().state().events().get(request)), 2400);
        var event = context.computeOnClient(client -> LumiClient.history().state().events().get(request));
        if (event.state() != OperationEventPayload.State.FAILED || !event.message().contains("overlaps")) {
            throw new AssertionError("Overlapping TCP Undo did not refuse the conflicting action: " + event);
        }
        snapshot("axiom_tcp_refused").assertMatches(conflicted, "Refused Undo changed the shared world");
        report.event("gate", "axiom_tcp_overlap", "succeeded", 0, 0, "conflicting Undo refused atomically");

        reset(baseline, before);
        beginPeer(PEER);
        tickPeer();
        awaitPackets(PEER, Blocks.EMERALD_BLOCK, 1);
        movePeer("minecraft:the_nether");
        tickPeer();
        // READY must route to the original dimension even though its participant moved.
        restore(baseline);
        mailbox.request(context, "axiom-ready", "minecraft:the_nether");
        tickPeer();
        peerAction("undo", false); // A server reply fences the preceding old-dimension TCP packet.
        snapshot("axiom_tcp_old_tail").assertMatches(before, "Old-dimension tail crossed completed Restore");
        server.runOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayer(peer);
            if (runtime(minecraft).axiomBufferActions().hasPending(player.connection)) {
                throw new AssertionError("Moved participant retained its old dispatch");
            }
        });
        movePeer("minecraft:overworld");
        beginPeer(PEER);
        tickPeer();
        awaitPackets(PEER, Blocks.EMERALD_BLOCK, 1);
        var oldConnection = server.computeOnServer(minecraft -> minecraft.getPlayerList().getPlayer(peer).connection);
        mailbox.request(context, "disconnect", "");
        await(() -> server.computeOnServer(minecraft -> minecraft.getPlayerList().getPlayerCount()) == 1,
                "Native editing peer did not disconnect");
        server.runOnServer(minecraft -> {
            if (runtime(minecraft).axiomBufferActions().hasPending(oldConnection)) {
                throw new AssertionError("Disconnected native producer retained its hold");
            }
        });
        restore(baseline);
        mailbox.request(context, "connect", "");
        mailbox.request(context, "axiom-ready", "minecraft:overworld");
        beginPeer(PEER);
        for (int packet = 1; packet <= 3; packet++) { tickPeer(); awaitPackets(PEER, Blocks.EMERALD_BLOCK, packet); }
        awaitComplete();
        peerAction("undo", true);
        snapshot("axiom_tcp_reconnected").assertMatches(before, "Reconnected native editing was inexact");
        operations.awaitDurability("axiom_tcp_complete");
        report.event("gate", "axiom_tcp_handoff_reconnect", "succeeded", 0, 0,
                "moved participant ACK; old-dimension packets refused; disconnect releases hold; new connection edits and Undo");

        local.begin(OWNER, Blocks.GOLD_BLOCK.defaultBlockState());
        for (int packet = 1; packet <= 3; packet++) { local.tick(); awaitPackets(OWNER, Blocks.GOLD_BLOCK, packet); }
        awaitComplete();
        mailbox.request(context, "axiom-pending", "");
        UUID replacing = context.computeOnClient(client -> LumiClient.networking().restore(baseline));
        mailbox.request(context, "axiom-pending-held", "");
        server.runOnServer(minecraft -> {
            var runtime = runtime(minecraft);
            if (!runtime.freeze().isFrozen() || !runtime.operations().hasActiveOperation()
                    || runtime.recoveryJournal().isPresent()
                    || !minecraft.overworld().getBlockState(OWNER).is(Blocks.GOLD_BLOCK)) {
                throw new AssertionError("Remote worker did not hold admission before journal creation/world writes");
            }
        });
        mailbox.request(context, "axiom-pending-release", "");
        succeeded(replacing);
        mailbox.request(context, "axiom-pending-check", "");
        snapshot("axiom_tcp_pending").assertMatches(before, "Remote stale calculation changed completed Restore");
        report.event("gate", "axiom_tcp_pending_work", "succeeded", 0, 0,
                "remote native worker ignores cancellation; admission held before writes; late tails before/after READY drained; history invalidated");
    }

    private void beginPeer(BlockPos origin) throws IOException {
        mailbox.request(context, "axiom-begin", origin.getX() + "," + origin.getY() + "," + origin.getZ());
    }

    private void tickPeer() throws IOException { mailbox.request(context, "axiom-tick", ""); }

    private void peerAction(String action, boolean success) throws IOException {
        String request = mailbox.request(context, action, "").getProperty("request");
        var reply = mailbox.request(context, "await", request);
        if (success && !reply.getProperty("state").equals("SUCCEEDED")) throw new AssertionError(reply.toString());
        if (!success && !List.of("SUCCEEDED", "FAILED").contains(reply.getProperty("state"))) {
            throw new AssertionError("New-dimension action did not finish safely: " + reply);
        }
    }

    private void movePeer(String dimension) throws IOException {
        server.runOnServer(minecraft -> minecraft.getCommands().performPrefixedCommand(
                minecraft.getPlayerList().getPlayer(peer).createCommandSourceStack(),
                "execute in " + dimension + " run tp @s 0 80 0"));
        mailbox.request(context, "axiom-ready", dimension);
    }

    private void reset(CommitId baseline, LumiWorldSnapshot before) throws IOException {
        restore(baseline);
        snapshot("axiom_tcp_reset").assertMatches(before, "TCP fixture reset was inexact");
        new LumiAxiomDispatchTestDriver(context).ready("minecraft:overworld");
        mailbox.request(context, "axiom-ready", "minecraft:overworld");
    }

    private void restore(CommitId target) {
        // These fixture resets qualify TCP admission; UI interaction has a separate gate.
        succeeded(context.computeOnClient(client -> LumiClient.networking().restore(target)));
    }

    private void succeeded(UUID request) {
        context.waitFor(client -> terminal(LumiClient.history().state().events().get(request)), 2400);
        var event = context.computeOnClient(client -> LumiClient.history().state().events().get(request));
        if (event.state() != OperationEventPayload.State.SUCCEEDED) throw new AssertionError(event.toString());
        await(() -> server.computeOnServer(minecraft -> !runtime(minecraft).operations().hasActiveOperation()
                && !runtime(minecraft).freeze().isFrozen()), "TCP operation did not release its dimension");
    }

    private void assertArriving(UUID actor) {
        server.runOnServer(minecraft -> {
            try {
                runtime(minecraft).liveActions().prepareUndo(actor);
                throw new AssertionError("Unfinished TCP dispatch was offered for Undo");
            } catch (IllegalStateException waiting) {
                if (!waiting.getMessage().contains("still arriving")) throw waiting;
            }
        });
    }

    private void awaitComplete() {
        await(() -> server.computeOnServer(minecraft -> minecraft.getPlayerList().getPlayers().stream()
                .noneMatch(player -> runtime(minecraft).axiomBufferActions().hasPending(player.connection))),
                "TCP dispatch END did not arrive");
    }

    private void awaitPackets(BlockPos origin, Block block, int expected) {
        await(() -> server.computeOnServer(minecraft -> java.util.stream.IntStream.range(0, 3)
                .filter(index -> minecraft.overworld().getBlockState(origin.offset(index * 16, 0, 0)).is(block))
                .count() == expected), "Native TCP section packet did not arrive: " + expected);
    }

    private void await(BooleanSupplier condition, String failure) {
        for (int tick = 0; tick < 2400; tick++) { if (condition.getAsBoolean()) return; context.waitTick(); }
        throw new AssertionError(failure);
    }

    private LumiWorldSnapshot snapshot(String name) throws IOException {
        return server.computeOnServer(minecraft -> LumiWorldSnapshot.capture(minecraft.overworld(), AREA, report, name));
    }

    private static boolean terminal(OperationEventPayload event) {
        return event != null && event.state() != OperationEventPayload.State.ACCEPTED
                && event.state() != OperationEventPayload.State.PROGRESS;
    }

    private static FabricDimensionRuntime runtime(MinecraftServer minecraft) {
        return LumiMod.serverRuntime().find(minecraft.overworld()).orElseThrow();
    }
}
