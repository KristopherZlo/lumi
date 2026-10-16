package io.github.lumi.minecraft.runtime;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread dispatch identity; nested handlers restore the previous scope. */
public final class AxiomBufferDispatchContext {
    private static final ThreadLocal<Active> CURRENT = new ThreadLocal<>();
    private AxiomBufferDispatchContext() { }

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
}
