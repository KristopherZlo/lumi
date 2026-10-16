package io.github.lumi.gametest;

import io.github.lumi.client.LumiClient;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.OperationEventPayload;
import java.io.IOException;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Runs the second actual Minecraft client without direct access to the server. */
final class LumiMultiplayerPeer {
    private final ClientGameTestContext context;
    private final LumiMultiplayerMailbox mailbox;
    private String address;
    private LumiAxiomDispatchTestDriver axiom;

    LumiMultiplayerPeer(ClientGameTestContext context, LumiMultiplayerMailbox mailbox) {
        this.context = context;
        this.mailbox = mailbox;
    }

    void run() throws IOException {
        address = mailbox.await(context, "server").getProperty("address");
        connect();
        for (int id = 1; ; id++) {
            Properties command = mailbox.await(context, "request-" + id);
            Properties reply = new Properties();
            String action = command.getProperty("action");
            try {
                execute(action, command.getProperty("argument"), reply);
            } catch (Throwable failed) {
                reply.setProperty("failure", failed.toString());
                mailbox.publish("reply-" + id, reply);
                throw new IllegalStateException("Multiplayer peer command failed", failed);
            }
            mailbox.publish("reply-" + id, reply);
            if (action.equals("stop")) return;
        }
    }

    private void execute(String action, String argument, Properties reply) {
        switch (action) {
            case "undo" -> reply.setProperty("request", context.computeOnClient(
                    client -> LumiClient.networking().undo()).toString());
            case "redo" -> reply.setProperty("request", context.computeOnClient(
                    client -> LumiClient.networking().redo()).toString());
            case "axiom-begin" -> {
                String[] coordinates = argument.split(",");
                axiom = new LumiAxiomDispatchTestDriver(context);
                axiom.begin(new net.minecraft.core.BlockPos(Integer.parseInt(coordinates[0]),
                        Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2])),
                        net.minecraft.world.level.block.Blocks.EMERALD_BLOCK.defaultBlockState());
            }
            case "axiom-tick" -> axiom.tick();
            case "axiom-ready" -> new LumiAxiomDispatchTestDriver(context).ready(argument);
            case "save" -> reply.setProperty("request", context.computeOnClient(
                    client -> LumiClient.networking().save(argument)).toString());
            case "restore" -> reply.setProperty("request", context.computeOnClient(
                    client -> LumiClient.networking().restore(
                            new CommitId(new ObjectId(argument)))).toString());
            case "await" -> {
                UUID request = UUID.fromString(argument);
                context.waitFor(client -> terminal(LumiClient.history().state().events().get(request)),
                        12000);
                var event = context.computeOnClient(client ->
                        LumiClient.history().state().events().get(request));
                reply.setProperty("state", event.state().name());
                reply.setProperty("head", event.head().hex());
                reply.setProperty("message", event.message());
            }
            case "head" -> {
                context.runOnClient(client -> LumiClient.networking().refreshSnapshot());
                context.waitFor(client -> LumiClient.history().state().snapshot()
                        .filter(snapshot -> snapshot.head().hex().equals(argument)).isPresent(), 12000);
                reply.setProperty("head", argument);
            }
            case "disconnect", "stop" -> {
                axiom = null;
                context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
            }
            case "connect" -> connect();
            case "break" -> {
                String[] coordinates = argument.split(",");
                var position = new net.minecraft.core.BlockPos(Integer.parseInt(coordinates[0]),
                        Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2]));
                context.waitFor(client -> client.player != null && client.level != null
                        && !client.level.getBlockState(position).isAir()
                        && client.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(position)) < 25,
                        2400);
                if (!context.computeOnClient(client -> {
                    client.hitResult = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(position),
                            net.minecraft.core.Direction.UP, position, false);
                    return client.gameMode.startDestroyBlock(position, net.minecraft.core.Direction.UP);
                })) {
                    throw new AssertionError("Peer refused the break packet");
                }
            }
            default -> throw new IllegalArgumentException("Unknown peer command: " + action);
        }
    }

    private void connect() {
        context.runOnClient(client -> {
            client.options.pauseOnLostFocus = false;
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("axiom")) {
                com.moulberry.axiom.Axiom.configuration.internal.shownIntroduction = true;
            }
            ConnectScreen.startConnecting(new TitleScreen(), client,
                    ServerAddress.parseString(address),
                    new ServerData("Lumi multiplayer", address, ServerData.Type.OTHER), false, null);
        });
        context.waitFor(client -> client.level != null && client.player != null, 2400);
        new LumiUiTestDriver(context).completeOnboardingIfShown();
        new LumiUiTestDriver(context).awaitHistory();
    }

    private static boolean terminal(OperationEventPayload event) {
        return event != null && event.state() != OperationEventPayload.State.ACCEPTED
                && event.state() != OperationEventPayload.State.PROGRESS;
    }
}
