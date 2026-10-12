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
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Two real editing clients, Save publication races, and server-owned Restore. */
final class LumiMultiplayerApplyScenario {
    private static final BlockPos PEER = new BlockPos(0, 1, 0);
    private static final BlockPos OWNER = new BlockPos(1, 1, 0);
    private static final List<BlockBox> AREA = List.of(new BlockBox(0, 0, 0, 15, 2, 15));
    private final ClientGameTestContext context;
    private final TestServerContext server;
    private final LumiBehaviorReport report;
    private final LumiMultiplayerMailbox mailbox;
    private final LumiBehaviorOperations operations;

    LumiMultiplayerApplyScenario(ClientGameTestContext context, TestServerContext server,
            LumiBehaviorReport report, LumiMultiplayerMailbox mailbox) {
        this.context = context;
        this.server = server;
        this.report = report;
        this.mailbox = mailbox;
        operations = new LumiBehaviorOperations(context, server, report);
    }

    void run() throws IOException {
        new LumiBehaviorActions(server, report).placeBlocks("multiplayer-probes", Items.STONE,
                List.of(PEER, OWNER));
        server.runOnServer(minecraft -> minecraft.getPlayerList().getPlayers().forEach(player -> {
            player.teleportTo(2.5, 1, 1.5);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.inventoryMenu.broadcastChanges();
        }));
        synchronizeHeads();
        var frozen = snapshot("multiplayer-before-capture");
        CommitId captured;
        try (var pause = LumiOperationTestPause.savePublication()) {
            UUID request = context.computeOnClient(client ->
                    LumiClient.networking().save("multiplayer-frozen-boundary"));
            await(() -> pause.reached() && server.computeOnServer(minecraft ->
                    !runtime(minecraft).freeze().isFrozen()
                            && runtime(minecraft).operations().hasActiveOperation()),
                    "Save did not release the dimension before publication");
            mailbox.request(context, "break", "0,1,0");
            context.waitFor(client -> client.player != null && client.level != null
                    && client.level.getBlockState(OWNER).is(Blocks.STONE)
                    && client.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(OWNER)) < 25);
            if (!context.computeOnClient(client ->
                    client.gameMode.startDestroyBlock(OWNER, Direction.UP))) {
                throw new AssertionError("Owner refused the break packet");
            }
            await(() -> server.computeOnServer(minecraft ->
                    minecraft.overworld().getBlockState(PEER).isAir()
                            && minecraft.overworld().getBlockState(OWNER).isAir()),
                    "Concurrent client edits did not reach the authoritative world");
            pause.close();
            captured = succeeded(request).head();
        }
        awaitIdle();
        if (operations.pendingBuilderKeyCount() == 0) {
            throw new AssertionError("Save publication cleared later client generations");
        }
        var edited = snapshot("multiplayer-after-packets");
        CommitId later = operations.save("multiplayer-later-edits");
        operations.restore("multiplayer-frozen-save", captured);
        snapshot("multiplayer-frozen-save").assertMatches(frozen, "Frozen Save changed after capture");
        operations.restore("multiplayer-later-save", later);
        snapshot("multiplayer-later-save").assertMatches(edited, "Later generations were lost");
        report.event("gate", "multiplayer_save_publication_edits", "succeeded", 0, 0,
                "clients=2;realBreakPackets=2;frozenBoundaryExact=true;laterGenerationsPreserved=true");
        synchronizeHeads();
        var level = server.computeOnServer(net.minecraft.server.MinecraftServer::overworld);
        try (var pause = LumiOperationTestPause.restoreAfterMutation(() ->
                level.getBlockState(PEER).is(Blocks.STONE))) {
            mailbox.request(context, "restore", captured.hex());
            await(pause::reached, "Restore did not reach its post-mutation boundary");
            server.runOnServer(minecraft -> {
                if (!runtime(minecraft).freeze().isFrozen()
                        || runtime(minecraft).recoveryJournal().isEmpty()) {
                    throw new AssertionError("Applied Restore lost its freeze or journal");
                }
            });
            mailbox.request(context, "disconnect", "");
            await(() -> server.computeOnServer(minecraft -> minecraft.getPlayerList().getPlayerCount()) == 1,
                    "Restore initiator did not disconnect");
            pause.close();
        }
        awaitIdle();
        if (!operations.activeCommit().equals(captured)) throw new AssertionError("Restore was abandoned");
        snapshot("multiplayer-disconnected-restore").assertMatches(frozen, "Disconnected Restore was inexact");
        mailbox.request(context, "connect", "");
        synchronizeHeads();
        report.event("gate", "multiplayer_applied_restore_disconnect", "succeeded", 0, 0,
                "initiatorDisconnectedAfterMutation=true;targetExact=true;journalCleared=true");
    }

    private LumiWorldSnapshot snapshot(String name) throws IOException {
        return server.computeOnServer(minecraft ->
                LumiWorldSnapshot.capture(minecraft.overworld(), AREA, report, name));
    }

    private OperationEventPayload succeeded(UUID request) {
        context.waitFor(client -> {
            var event = LumiClient.history().state().events().get(request);
            return event != null && event.state() != OperationEventPayload.State.ACCEPTED
                    && event.state() != OperationEventPayload.State.PROGRESS;
        }, 12000);
        var event = context.computeOnClient(client -> LumiClient.history().state().events().get(request));
        if (event.state() != OperationEventPayload.State.SUCCEEDED) {
            throw new AssertionError("Multiplayer Save failed: " + event.message());
        }
        return event;
    }

    private void synchronizeHeads() throws IOException {
        awaitIdle();
        String head = operations.activeCommit().hex();
        context.runOnClient(client -> LumiClient.networking().refreshSnapshot());
        context.waitFor(client -> LumiClient.history().state().snapshot()
                .filter(snapshot -> snapshot.head().hex().equals(head)).isPresent(), 12000);
        mailbox.request(context, "head", head);
    }

    private void awaitIdle() {
        await(() -> server.computeOnServer(minecraft -> {
            var runtime = runtime(minecraft);
            return !runtime.operations().hasActiveOperation() && runtime.operations().queuedCount() == 0
                    && !runtime.freeze().isFrozen() && runtime.recoveryJournal().isEmpty();
        }), "Multiplayer operation did not settle");
    }

    private void await(BooleanSupplier condition, String failure) {
        for (int tick = 0; tick < 12000; tick++) {
            if (condition.getAsBoolean()) return;
            context.waitTick();
        }
        throw new AssertionError(failure);
    }

    private static FabricDimensionRuntime runtime(net.minecraft.server.MinecraftServer minecraft) {
        return LumiMod.serverRuntime().find(minecraft.overworld()).orElseThrow();
    }
}
