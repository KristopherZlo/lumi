package io.github.lumi.client.integration;

import com.moulberry.axiom.clipboard.Placement;
import com.moulberry.axiom.tools.Tool;
import com.moulberry.axiom.tools.ToolManager;
import com.moulberry.axiom.world_modification.Dispatcher;
import io.github.lumi.LumiMod;
import io.github.lumi.mixin.client.AxiomDispatcherPendingAccess;
import io.github.lumi.minecraft.runtime.MinecraftPlayerMessages;
import io.github.lumi.network.BuilderToolBarrierPayload;
import io.github.lumi.network.BuilderToolBarrierPayload.State;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Cancels pending native work, then proves worker quiescence without blocking a render tick. */
public final class AxiomClientWorkBarrier {
    private static final AxiomClientWorkBarrier INSTANCE = new AxiomClientWorkBarrier();
    private final Map<UUID, BuilderToolBarrierPayload> requests = new LinkedHashMap<>();
    private volatile boolean paused;
    private QuiescentToolExecutor singleExecutor;
    private int quietTicks;
    private long deadline;
    private volatile long generation;
    private State acknowledgement;
    private String historyIdentifier;
    private boolean managedConnection;
    private boolean localAdmission;

    private AxiomClientWorkBarrier() { }

    public static boolean isPaused() { return INSTANCE.paused; }
    public static long generation() { return INSTANCE.generation; }

    public static ExecutorService singleThreadExecutor(ThreadFactory factory) {
        INSTANCE.singleExecutor = new QuiescentToolExecutor(factory);
        return Executors.unconfigurableExecutorService(INSTANCE.singleExecutor);
    }

    public static void historyChanged(String identifier) {
        var client = Minecraft.getInstance();
        if (identifier == null || client.getConnection() == null
                || !ClientPlayNetworking.canSend(BuilderToolBarrierPayload.TYPE)) return;
        INSTANCE.managedConnection = true;
        if (Objects.equals(INSTANCE.historyIdentifier, identifier)) return;
        INSTANCE.historyIdentifier = identifier;
        INSTANCE.localAdmission = true;
        INSTANCE.beginPause(true);
    }

    public static void register() {
        INSTANCE.registerNetworking();
    }

    private void registerNetworking() {
        ClientPlayNetworking.registerGlobalReceiver(BuilderToolBarrierPayload.TYPE,
                (payload, context) -> context.client().execute(() -> accept(payload)));
        ClientTickEvents.END_CLIENT_TICK.register(client -> { tick(client); AxiomWorldHistory.INSTANCE.tick(); });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            paused = true;
            try {
                if (managedConnection) cancelPending();
            } catch (RuntimeException failure) {
                LumiMod.LOGGER.warn("Cannot cancel Axiom work on disconnect", failure);
            } finally {
                requests.clear();
                paused = false;
                acknowledgement = null;
                managedConnection = false;
                localAdmission = false;
                historyIdentifier = null;
                AxiomWorldHistory.INSTANCE.disconnect();
                generation++;
            }
        });
    }

    private void accept(BuilderToolBarrierPayload payload) {
        if (payload.state() == State.RESUME) {
            BuilderToolBarrierPayload request = requests.get(payload.barrier());
            if (request == null || !request.dimension().equals(payload.dimension())) return;
            requests.remove(payload.barrier());
            if (requests.isEmpty() && !localAdmission) {
                localAdmission = true;
                beginPause(false);
            }
            return;
        }
        if (payload.state() != State.PAUSE || requests.containsKey(payload.barrier())) return;
        if (requests.size() >= 64) {
            reply(payload, State.REFUSED);
            return;
        }
        requests.put(payload.barrier(), payload);
        managedConnection = true;
        if (paused) {
            if (acknowledgement != null) reply(payload, acknowledgement);
            return;
        }
        beginPause(true);
    }

    private void beginPause(boolean invalidateHistory) {
        paused = true;
        generation++;
        acknowledgement = null;
        quietTicks = 0;
        deadline = System.nanoTime() + 10_000_000_000L;
        try {
            cancelPending();
            if (invalidateHistory) {
                if (AxiomWorldHistory.INSTANCE.shared()) AxiomDispatcherPendingAccess.lumi$history().clear();
                Dispatcher.clearActiveSelectionHistory();
            }
            if (Tool.sharedSingleThreadExecutor == null || singleExecutor == null) {
                throw new IllegalStateException("Native Axiom worker is not tracked");
            }
            LumiMod.LOGGER.debug("Axiom work cancellation requested; transient cursor reset={}", invalidateHistory);
            if (workersQuiescent()) finish(State.READY);
        } catch (RuntimeException failure) {
            LumiMod.LOGGER.error("Cannot quiesce pending Axiom work", failure);
            finish(State.REFUSED);
        }
    }

    private void tick(Minecraft client) {
        if (!paused || acknowledgement != null) return;
        if (System.nanoTime() >= deadline) {
            finish(State.REFUSED);
            return;
        }
        if (!workersQuiescent()) {
            quietTicks = 0;
            return;
        }
        if (++quietTicks < 2) return;
        try {
            // A cancelled calculation can ignore interruption and finish after the first reset.
            cancelPending();
            finish(State.READY);
        } catch (RuntimeException failure) {
            LumiMod.LOGGER.error("Cannot finalize Axiom quiescence", failure);
            finish(State.REFUSED);
        }
    }

    private boolean workersQuiescent() {
        return singleExecutor != null && singleExecutor.isQuiescent()
                && Tool.sharedPoolThreadExecutor.isQuiescent()
                && Tool.sharedPoolThreadExecutor.getQueuedTaskCount() == 0
                && Tool.sharedPoolThreadExecutor.getQueuedSubmissionCount() == 0;
    }

    private void cancelPending() {
        com.moulberry.axiom.ContextMenuManager.getInstance().close();
        Placement.INSTANCE.stopPlacement();
        ToolManager.resetAll();
        AxiomDispatcherPendingAccess.lumi$dispatchTasks().clear();
        AxiomDispatcherPendingAccess.lumi$chunkCallbacks().clear();
        AxiomDispatcherPendingAccess.lumi$pendingChunks().clear();
        AxiomDispatcherPendingAccess.lumi$chunkTimes().clear();
        AxiomDispatcherPendingAccess.lumi$entityCallbacks().clear();
        AxiomDispatcherPendingAccess.lumi$pendingEntities().clear();
        AxiomDispatcherPendingAccess.lumi$entityTimes().clear();
        AxiomDispatcherPendingAccess.lumi$tracers().clear();
    }

    private void finish(State state) {
        acknowledgement = state;
        for (BuilderToolBarrierPayload request : requests.values()) reply(request, state);
        if (state == State.READY) localAdmission = false;
        if (requests.isEmpty() && state == State.READY) {
            paused = false;
        } else if (requests.isEmpty() && state == State.REFUSED && Minecraft.getInstance().player != null) {
            MinecraftPlayerMessages.chat(Minecraft.getInstance().player, Component.translatable("lumi.axiom.sync_refused"));
        }
    }

    private void reply(BuilderToolBarrierPayload request, State state) {
        ClientPlayNetworking.send(new BuilderToolBarrierPayload(request.barrier(), request.dimension(), state));
    }
}
