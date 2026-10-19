package io.github.lumi.client.integration;

import com.moulberry.axiom.world_modification.BlockOrBiomeBuffer;
import com.moulberry.axiom.world_modification.Dispatcher;
import com.moulberry.axiom.world_modification.HistoryBuffer;
import com.moulberry.axiom.world_modification.HistoryEntry;
import io.github.lumi.client.LumiClient;
import io.github.lumi.domain.model.WorldActionView;
import io.github.lumi.mixin.client.AxiomDispatcherPendingAccess;
import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.network.WorldActionViewPayload;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Axiom renders the server cursor; only the server owns replay data and cursor movement. */
public final class AxiomWorldHistory {
    public static final AxiomWorldHistory INSTANCE = new AxiomWorldHistory();
    private final IdentityHashMap<HistoryEntry<?>, UUID> rows = new IdentityHashMap<>();
    private final BlockOrBiomeBuffer empty = new MirrorBuffer();
    private HistoryBuffer<BlockOrBiomeBuffer> mirroredBuffer;
    private List<WorldActionView.Entry> mirroredUndo = List.of(), mirroredRedo = List.of();
    private UUID mirroredEpoch;
    private UUID target;
    private boolean navigating;

    private AxiomWorldHistory() { }

    public boolean managed() { return LumiClient.networking().worldActions().isPresent(); }

    private boolean ready() {
        var client = Minecraft.getInstance();
        return client.level != null && LumiClient.networking().worldActions()
                .filter(value -> value.dimension().equals(client.level.dimension().identifier().toString())).isPresent();
    }

    public boolean step(boolean undo) {
        if (!managed()) return false;
        if (ready() && !AxiomClientWorkBarrier.isPaused()) {
            if (undo) LumiClient.networking().undo(); else LumiClient.networking().redo();
        }
        return true;
    }

    public boolean moveTo(int position) {
        if (!managed()) return false;
        if (!ready()) return true;
        var data = Dispatcher.getHistoryData(position);
        if (position != -1 && (data == null || !rows.containsKey(data.entry()))) return false;
        if (!AxiomClientWorkBarrier.isPaused() && !LumiClient.networking().worldActionPending()) {
            target = position == -1 ? null : rows.get(data.entry());
            navigating = true;
            Dispatcher.clearActiveSelectionHistory();
        }
        return true;
    }

    public void accept(OperationEventPayload event) {
        if (event.state() != OperationEventPayload.State.ACCEPTED
                && event.state() != OperationEventPayload.State.PROGRESS
                && event.state() != OperationEventPayload.State.SUCCEEDED) navigating = false;
    }

    public void cancelNavigation() { navigating = false; }

    public boolean clear() {
        if (!managed()) return false;
        if (ready() && !AxiomClientWorkBarrier.isPaused() && !LumiClient.networking().worldActionPending()) {
            navigating = false;
            LumiClient.networking().clearWorldActions();
        }
        return true;
    }

    public void tick() {
        if (!ready() || AxiomClientWorkBarrier.isPaused()) return;
        var payload = LumiClient.networking().worldActions().orElseThrow();
        mirror(payload);
        if (!navigating || LumiClient.networking().worldActionPending()) return;
        var view = payload.view();
        var entries = entries(view);
        int wanted = target == null ? -1 : -2;
        for (int index = 0; index < entries.size(); index++) if (entries.get(index).action().equals(target)) wanted = index;
        int current = view.undo().size() - 1;
        if (wanted == -2 || wanted == current) { navigating = false; return; }
        LumiClient.networking().replayWorldStep(wanted < current);
    }

    private static List<WorldActionView.Entry> entries(WorldActionView view) {
        var entries = new ArrayList<>(view.undo());
        entries.addAll(view.redo());
        return entries;
    }

    private void mirror(WorldActionViewPayload payload) {
        AxiomDispatcherPendingAccess.lumi$applyPendingHistory();
        var buffer = AxiomDispatcherPendingAccess.lumi$history();
        var view = payload.view();
        if (mirroredEpoch != null && !mirroredEpoch.equals(payload.epoch())) navigating = false;
        if (buffer == mirroredBuffer && payload.epoch().equals(mirroredEpoch)
                && view.undo().equals(mirroredUndo) && view.redo().equals(mirroredRedo)
                && buffer.getSize() == view.undo().size() + view.redo().size()
                && buffer.getPosition() == view.undo().size() - 1) return;
        buffer.clear();
        rows.clear();
        for (var action : entries(view)) {
            BlockPos origin = action.origin().map(value -> new BlockPos(value.x(), value.y(), value.z()))
                    .orElseGet(() -> Minecraft.getInstance().player.blockPosition());
            var row = new HistoryEntry<BlockOrBiomeBuffer>(empty, empty, origin,
                    Component.translatable("lumi.history.action." + action.kind().name().toLowerCase(java.util.Locale.ROOT)).getString(),
                    HistoryEntry.MODIFIER_CAN_BE_UNDONE_INGAME);
            buffer.push(row);
            rows.put(row, action.action());
        }
        buffer.unsafeSetPosition(view.undo().size() - 1);
        // Local selection/tool history cannot refer to positions from the previous world cursor.
        Dispatcher.clearActiveSelectionHistory();
        mirroredBuffer = buffer;
        mirroredEpoch = payload.epoch();
        mirroredUndo = view.undo();
        mirroredRedo = view.redo();
    }

    public void disconnect() {
        if (mirroredBuffer != null) mirroredBuffer.clear();
        mirroredBuffer = null;
        mirroredEpoch = null;
        mirroredUndo = mirroredRedo = List.of();
        rows.clear();
        navigating = false;
        target = null;
    }

    /** Display rows have no native replay data and must never be applied or persisted. */
    private record MirrorBuffer() implements BlockOrBiomeBuffer { }
}
