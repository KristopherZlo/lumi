package io.github.lumi.client.integration;

import io.github.lumi.client.LumiClient;
import io.github.lumi.domain.model.AxiomEntryIdentity;
import java.util.UUID;
import java.util.Optional;
import io.github.lumi.network.AxiomDispatchPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.moulberry.axiom.packets.AxiomServerboundPacket;
import com.moulberry.axiom.packets.AxiomServerboundSpawnEntity;
import com.moulberry.axiom.packets.AxiomServerboundDeleteEntity;
import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import net.minecraft.client.Minecraft;

/** Explicit native gesture scope, carried through native asynchronous data callbacks. */
public final class AxiomHistoryCaptureScope implements AutoCloseable {
    public record Token(AxiomEntryIdentity identity, String dimension, long generation) {
        public boolean valid() {
            return AxiomWorldHistory.INSTANCE.nativeMode() && generation == AxiomClientWorkBarrier.generation()
                    && Minecraft.getInstance().level != null
                    && dimension.equals(Minecraft.getInstance().level.dimension().identifier().toString())
                    && LumiClient.networking().worldActions().filter(view -> identity.epoch().equals(view.epoch())
                        && dimension.equals(view.dimension())).isPresent();
        }
    }
    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();
    private final Token previous;

    private AxiomHistoryCaptureScope(Token token) {
        previous = CURRENT.get();
        if (token == null) CURRENT.remove(); else CURRENT.set(token);
    }

    public static Token current() { return CURRENT.get(); }
    public static boolean validCurrent() { return current() == null || current().valid(); }
    public static Token capture() {
        if (current() != null) return current();
        if (!AxiomWorldHistory.INSTANCE.nativeMode()) return null;
        return LumiClient.networking().worldActions().map(view -> new Token(
                new AxiomEntryIdentity(view.epoch(), UUID.randomUUID()), view.dimension(), AxiomClientWorkBarrier.generation())).orElse(null);
    }
    public static AxiomHistoryCaptureScope open() { return new AxiomHistoryCaptureScope(capture()); }
    public static void run(Token token, Runnable work) {
        if (token == null) { work.run(); return; }
        if (!token.valid()) return;
        try (var ignored = new AxiomHistoryCaptureScope(token)) { work.run(); }
    }

    public static void send(AxiomServerboundPacket packet, Runnable original) {
        if (AxiomClientWorkBarrier.isPaused() || !validCurrent()) return;
        boolean entity = packet instanceof AxiomServerboundSpawnEntity || packet instanceof AxiomServerboundDeleteEntity
                || packet instanceof AxiomServerboundManipulateEntity;
        if (entity && !AxiomWorldHistory.INSTANCE.admissionReady()) return;
        Token token = current();
        if (!entity || !ClientPlayNetworking.canSend(AxiomDispatchPayload.TYPE)) { original.run(); return; }
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        String dimension = level.dimension().identifier().toString();
        var identity = Optional.ofNullable(token).map(Token::identity);
        UUID dispatch = UUID.randomUUID();
        var replay = Optional.ofNullable(AxiomNativeReplayClient.current()).map(AxiomNativeReplayClient.Token::request);
        ClientPlayNetworking.send(new AxiomDispatchPayload(dispatch, dimension, false, identity, true, replay));
        try { original.run(); }
        finally { ClientPlayNetworking.send(new AxiomDispatchPayload(dispatch, dimension, true, identity, true, replay)); }
    }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
