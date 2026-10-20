package io.github.lumi.minecraft.runtime;

import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.lumi.network.WorldActionViewPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread dispatch identity; nested handlers restore the previous scope. */
public final class AxiomBufferDispatchContext {
    private static final ThreadLocal<Active> CURRENT = new ThreadLocal<>();
    private static final Map<Object, Connection> CONNECTIONS = new WeakHashMap<>();
    private AxiomBufferDispatchContext() { }

    public record EntityScope(String dimension, UUID dispatch) { }
    public static Optional<EntityScope> entity(Object connection) {
        var state = CONNECTIONS.get(connection);
        return state == null ? Optional.empty() : Optional.ofNullable(state.entity);
    }
    public static void beginEntity(Object connection, String dimension, UUID dispatch) {
        CONNECTIONS.computeIfAbsent(connection, ignored -> new Connection()).entity = new EntityScope(dimension, dispatch);
    }
    public static void completeEntity(Object connection, UUID dispatch) {
        var state = CONNECTIONS.get(connection);
        if (state != null && state.entity != null && state.entity.dispatch().equals(dispatch)) state.entity = null;
    }
    public static void disconnect(Object connection) { CONNECTIONS.remove(connection); }
    public static void clear() { CONNECTIONS.clear(); }

    public static boolean admitsEntity(FabricDimensionRuntime runtime, ServerPlayer player) {
        var scope = entity(player.connection);
        if (scope.isPresent()) return scope.get().dimension().equals(runtime.level().dimension().identifier().toString())
                && runtime.axiomBufferActions().admits(player.connection, player.getUUID(), scope.get().dispatch());
        // Trusted direct server API calls have no network envelope (including mock GameTest players).
        if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player) return true;
        if (!ServerPlayNetworking.canSend(player, WorldActionViewPayload.TYPE)) {
            var state = CONNECTIONS.computeIfAbsent(player.connection, ignored -> new Connection());
            if (!state.warned) {
                state.warned = true;
                MinecraftPlayerMessages.chat(player, Component.literal("Install or update Lumi on the Axiom client to coordinate editing history."));
            }
        }
        return false;
    }

    public static Optional<ServerPlayer> actor() { return Optional.ofNullable(CURRENT.get()).map(Active::player); }

    public static Optional<UUID> current(ServerPlayer player) {
        Active active = CURRENT.get();
        return active != null && active.player == player ? Optional.of(active.dispatch) : Optional.empty();
    }

    public static void run(ServerPlayer player, UUID dispatch, Runnable task) {
        Active previous = CURRENT.get();
        CURRENT.set(new Active(player, dispatch));
        try {
            task.run();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    private record Active(ServerPlayer player, UUID dispatch) { }
    private static final class Connection { private EntityScope entity; private boolean warned; }
}
