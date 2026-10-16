package io.github.lumi.minecraft.runtime;

import io.github.lumi.minecraft.world.WorldReplacementBarrier;
import io.github.lumi.network.BuilderToolBarrierPayload;
import io.github.lumi.network.BuilderToolBarrierPayload.State;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread, connection-bound admission; never waits on a disconnected builder. */
public final class MinecraftAxiomWorldBarrier implements WorldReplacementBarrier {
    //? if >=26.1 {
    /*private static final Identifier AXIOM_CLIENT_CHANNEL = Identifier.fromNamespaceAndPath("axiom", "goodbye");
    *///?} else {
    private static final Identifier AXIOM_CLIENT_CHANNEL = Identifier.fromNamespaceAndPath("axiom", "enable");
    //?}
    private static final long TIMEOUT_NANOS = 15_000_000_000L;
    private final ServerLevel level;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private UUID barrier;
    private boolean refused;

    public MinecraftAxiomWorldBarrier(ServerLevel level) { this.level = level; }

    @Override public boolean awaitReady() throws IOException {
        if (barrier == null) barrier = UUID.randomUUID();
        try {
            return synchronize(false);
        } catch (IOException failure) {
            refused = true;
            throw failure;
        }
    }

    @Override public boolean isReleaseReady() {
        if (barrier == null || refused) return true;
        try {
            return synchronize(true);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public void acknowledge(ServerPlayer player, BuilderToolBarrierPayload payload) {
        if (!payload.barrier().equals(barrier) || !payload.dimension().equals(dimension())) return;
        Participant participant = participants.get(player.getUUID());
        if (participant != null && participant.player == player
                && (payload.state() == State.READY || payload.state() == State.REFUSED)) {
            participant.state = payload.state();
        }
    }

    private boolean synchronize(boolean releasing) throws IOException {
        participants.values().removeIf(participant -> {
            if (connected(participant.player)) return false;
            if (sameConnection(participant.player)) send(participant.player, State.RESUME);
            return true;
        });
        for (ServerPlayer player : level.players()) {
            if (!connected(player) || participants.containsKey(player.getUUID())
                    || !ServerPlayNetworking.canSend(player, AXIOM_CLIENT_CHANNEL)) continue;
            if (!ServerPlayNetworking.canSend(player, BuilderToolBarrierPayload.TYPE)) {
                String reason = "Lumi cannot synchronize Axiom on this client; update Lumi before restoring";
                if (!releasing) throw new IOException(reason);
                player.connection.disconnect(Component.literal(reason));
                continue;
            }
            participants.put(player.getUUID(), new Participant(player));
            send(player, State.PAUSE);
        }
        var iterator = participants.values().iterator();
        boolean ready = true;
        while (iterator.hasNext()) {
            Participant participant = iterator.next();
            if (participant.state == State.READY) continue;
            if (participant.state == State.REFUSED || System.nanoTime() >= participant.deadline) {
                String reason = "Lumi could not drain pending Axiom work before world replacement";
                if (!releasing) throw new IOException(reason);
                participant.player.connection.disconnect(Component.literal(reason));
                iterator.remove();
            } else {
                ready = false;
            }
        }
        return ready;
    }

    @Override public void released() {
        for (Participant participant : participants.values()) {
            if (sameConnection(participant.player)) send(participant.player, State.RESUME);
        }
        participants.clear();
        barrier = null;
        refused = false;
    }

    private boolean connected(ServerPlayer player) {
        return player.level() == level && sameConnection(player);
    }

    private boolean sameConnection(ServerPlayer player) {
        return level.getServer().getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private String dimension() { return level.dimension().identifier().toString(); }

    private void send(ServerPlayer player, State state) {
        ServerPlayNetworking.send(player, new BuilderToolBarrierPayload(barrier, dimension(), state));
    }

    private static final class Participant {
        private final ServerPlayer player;
        private final long deadline = System.nanoTime() + TIMEOUT_NANOS;
        private State state;
        private Participant(ServerPlayer player) { this.player = player; }
    }
}
