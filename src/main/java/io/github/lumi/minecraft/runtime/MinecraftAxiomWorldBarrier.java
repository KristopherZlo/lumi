package io.github.lumi.minecraft.runtime;

import io.github.lumi.minecraft.world.WorldReplacementBarrier;
import io.github.lumi.network.BuilderToolBarrierPayload;
import io.github.lumi.network.BuilderToolBarrierPayload.State;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Server-thread, connection-bound admission; never waits on a disconnected builder. */
public final class MinecraftAxiomWorldBarrier implements WorldReplacementBarrier {
    //? if >=26.1 {
    /*private static final Identifier AXIOM_CLIENT_CHANNEL = Identifier.fromNamespaceAndPath("axiom", "goodbye");
    *///?} else {
    private static final Identifier AXIOM_CLIENT_CHANNEL = Identifier.fromNamespaceAndPath("axiom", "enable");
    //?}
    private static final long TIMEOUT_NANOS = 15_000_000_000L;
    private final ServerLevel level;
    private final Consumer<ServerGamePacketListenerImpl> drained;
    private final Predicate<ServerGamePacketListenerImpl> pending;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private UUID barrier;
    private boolean refused;

    public MinecraftAxiomWorldBarrier(ServerLevel level, Consumer<ServerGamePacketListenerImpl> drained,
            Predicate<ServerGamePacketListenerImpl> pending) {
        this.level = level;
        this.drained = java.util.Objects.requireNonNull(drained, "drained");
        this.pending = java.util.Objects.requireNonNull(pending, "pending");
    }

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
        if (participant != null && participant.connection == player.connection
                && (payload.state() == State.READY || payload.state() == State.REFUSED)) {
            boolean firstReady = participant.state != State.READY && payload.state() == State.READY;
            participant.state = payload.state();
            if (firstReady) drained.accept(participant.connection);
        }
    }

    private boolean synchronize(boolean releasing) throws IOException {
        participants.values().removeIf(participant -> {
            if (currentPlayer(participant) != null) return false;
            drained.accept(participant.connection);
            return true;
        });
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level && !pending.test(player.connection)) continue;
            if (!sameConnection(player) || participants.containsKey(player.getUUID())
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
                participant.connection.disconnect(Component.literal(reason));
                iterator.remove();
            } else {
                ready = false;
            }
        }
        return ready;
    }

    @Override public void released() {
        for (Participant participant : participants.values()) {
            ServerPlayer player = currentPlayer(participant);
            if (player != null) send(player, State.RESUME);
        }
        participants.clear();
        barrier = null;
        refused = false;
    }

    private boolean sameConnection(ServerPlayer player) {
        ServerPlayer current = level.getServer().getPlayerList().getPlayer(player.getUUID());
        return current != null && current.connection == player.connection;
    }

    private ServerPlayer currentPlayer(Participant participant) {
        ServerPlayer current = level.getServer().getPlayerList().getPlayer(participant.player);
        return current != null && current.connection == participant.connection ? current : null;
    }

    private String dimension() { return level.dimension().identifier().toString(); }

    private void send(ServerPlayer player, State state) {
        ServerPlayNetworking.send(player, new BuilderToolBarrierPayload(barrier, dimension(), state));
    }

    private static final class Participant {
        private final UUID player;
        private final ServerGamePacketListenerImpl connection;
        private final long deadline = System.nanoTime() + TIMEOUT_NANOS;
        private State state;
        private Participant(ServerPlayer player) {
            this.player = player.getUUID();
            connection = player.connection;
        }
    }
}
