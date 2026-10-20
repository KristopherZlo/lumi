package io.github.lumi.client.integration;

import io.github.lumi.network.AxiomNativeReplayPayload;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Carries one admitted native invocation through its real queues and callbacks. */
public final class AxiomNativeReplayClient {
    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();
    private AxiomNativeReplayClient() { }
    public static Token current() { return CURRENT.get(); }

    public static void execute(UUID request, String dimension, Runnable nativeCall) {
        var token = new Token(request, dimension, AxiomClientWorkBarrier.generation());
        try { run(token, nativeCall); }
        finally { token.release(); }
    }

    public static void run(Token token, Runnable work) {
        if (token == null) { work.run(); return; }
        var previous = CURRENT.get();
        CURRENT.set(token);
        try { work.run(); }
        catch (RuntimeException | Error failure) { token.failed = true; throw failure; }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }

    public static final class Token {
        private final UUID request;
        private final String dimension;
        private final long generation;
        private final AtomicInteger remaining = new AtomicInteger(1);
        private volatile boolean failed;
        private Token(UUID request, String dimension, long generation) {
            this.request = request; this.dimension = dimension; this.generation = generation;
        }
        public UUID request() { return request; }
        public Token retain() { remaining.incrementAndGet(); return this; }
        public void fail() { failed = true; }
        public void release() {
            if (remaining.decrementAndGet() != 0) return;
            Minecraft.getInstance().execute(() -> {
                if (generation == AxiomClientWorkBarrier.generation() && ClientPlayNetworking.canSend(AxiomNativeReplayPayload.TYPE))
                    ClientPlayNetworking.send(new AxiomNativeReplayPayload(request, dimension,
                            failed ? AxiomNativeReplayPayload.State.FAILED : AxiomNativeReplayPayload.State.SENT));
            });
        }
    }
}
