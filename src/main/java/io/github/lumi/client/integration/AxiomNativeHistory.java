package io.github.lumi.client.integration;

import com.moulberry.axiom.world_modification.BlockOrBiomeBuffer;
import com.moulberry.axiom.world_modification.DummyBuffer;
import com.moulberry.axiom.world_modification.HistoryBuffer;
import com.moulberry.axiom.world_modification.HistoryEntry;
import com.moulberry.axiom.world_modification.HistoryIO;
import com.moulberry.axiom.world_modification.undo.CreatePathAdditionalUndoOperation;
import com.moulberry.axiom.world_modification.undo.ModellingAdditionalUndoOperation;
import io.github.lumi.client.LumiClient;
import io.github.lumi.domain.model.WorldActionView;
import io.github.lumi.minecraft.runtime.MinecraftPlayerMessages;
import io.github.lumi.mixin.client.AxiomDispatcherPendingAccess;
import io.github.lumi.network.WorldActionViewPayload;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Native payloads stay in Axiom; its visible cursor projects confirmed server groups. */
final class AxiomNativeHistory {
    private static final int MAX_ENTRIES = 16_384, MAX_PENDING = 64;
    private final TreeMap<Integer, Row> rows = new TreeMap<>();
    private final Map<UUID, Row> byId = new HashMap<>();
    private final Map<BlockOrBiomeBuffer, AxiomNativeHistoryFiles.Binding> buffers = new IdentityHashMap<>();
    private final LinkedHashMap<UUID, Boolean> pending = new LinkedHashMap<>();
    private final Map<HistoryEntry<?>, UUID> controls = new IdentityHashMap<>();
    private final Map<UUID, Boolean> confirmed = new HashMap<>();
    private final HistoryBuffer<BlockOrBiomeBuffer> display = new HistoryBuffer<>();
    private HistoryBuffer<BlockOrBiomeBuffer> actual;
    private long revision = -1;
    private long rawRevision = -1;
    private UUID target;
    private boolean navigating;
    private boolean localReplay;

    HistoryEntry<BlockOrBiomeBuffer> push(HistoryEntry<BlockOrBiomeBuffer> entry, WorldActionViewPayload payload) {
        adopt();
        syncRawCursor(payload);
        if (AxiomNativeHistoryFiles.binding(entry) != null) entry = new HistoryEntry<>(entry.forwards(), entry.backwards(),
                entry.origin(), entry.description(), entry.bytes(), entry.modifiers(), entry.additionalUndoOperation());
        int position = actual.getPosition() + 1;
        for (var row : List.copyOf(rows.tailMap(position).values())) forget(row);
        var token = AxiomHistoryCaptureScope.current();
        var binding = new AxiomNativeHistoryFiles.Binding(payload.epoch(), token == null ? UUID.randomUUID() : token.identity().entry(), position);
        add(new Row(entry, binding));
        if (!(entry.forwards() instanceof DummyBuffer)) buffers.put(entry.forwards(), binding);
        pending.put(binding.entry(), true);
        if (pending.size() > MAX_PENDING) pending.remove(pending.keySet().iterator().next());
        navigating = false;
        revision = -1;
        return entry;
    }

    AxiomNativeHistoryFiles.Binding entry(BlockOrBiomeBuffer buffer) { return buffers.remove(buffer); }

    HistoryBuffer<BlockOrBiomeBuffer> projection(WorldActionViewPayload payload) {
        adopt();
        if (revision == payload.view().revision()) return display;
        var known = new HashSet<UUID>();
        java.util.stream.Stream.concat(payload.view().undo().stream(), payload.view().redo().stream())
                .forEach(group -> known.addAll(group.axiomEntries()));
        pending.keySet().removeAll(known);
        controls.clear(); display.clear();
        addGroups(payload.view().undo(), payload.epoch());
        addPending(true);
        int position = display.getSize() - 1;
        addPending(false);
        addGroups(payload.view().redo(), payload.epoch());
        display.unsafeSetPosition(position);
        revision = payload.view().revision();
        return display;
    }

    boolean step(boolean undo, WorldActionViewPayload payload) {
        if (localReplay) return false;
        var projected = projection(payload);
        int position = projected.getPosition() + (undo ? 0 : 1);
        if (position < 0 || position >= projected.getSize()) return true;
        var entry = projected.getHistoryEntry(position);
        UUID id = controls.get(entry);
        Row row = byId.get(id);
        if (row == null || !row.binding.epoch().equals(payload.epoch())) { unavailable(); return true; }
        if (pending.containsKey(id)) {
            if (!localOnly(entry)) { unavailable(); return true; }
            pending.put(id, !undo);
            actual.unsafeSetPosition(undo ? row.binding.position() : row.binding.position() - 1);
            revision = -1;
            return false; // Axiom alone owns this client-only tool callback.
        }
        LumiClient.networking().replayAxiomEntry(id, undo);
        return true;
    }

    boolean moveTo(int position, WorldActionViewPayload payload) {
        projection(payload);
        if (position < -1 || position >= display.getSize()) return true;
        target = position == -1 ? null : controls.get(display.getHistoryEntry(position));
        navigating = position == -1 || target != null;
        return true;
    }

    void tick(WorldActionViewPayload payload) {
        projection(payload);
        if (rawRevision != payload.view().revision()) {
            syncLocalTools(payload);
            syncRawCursor(payload); rawRevision = payload.view().revision();
        }
        if (!navigating || LumiClient.networking().worldActionPending()) return;
        int wanted = -1;
        if (target != null) {
            wanted = -2;
            for (int i = 0; i < display.getSize(); i++) if (target.equals(controls.get(display.getHistoryEntry(i)))) wanted = i;
        }
        int current = display.getPosition();
        if (wanted == -2 || wanted == current) { navigating = false; return; }
        boolean undo = wanted < current;
        if (!step(undo, payload)) {
            localReplay = true;
            try {
                if (undo) AxiomDispatcherPendingAccess.lumi$undo(false, false, false);
                else AxiomDispatcherPendingAccess.lumi$redo(false, false);
            } finally { localReplay = false; }
        }
    }

    void cancelNavigation() { navigating = false; }

    void clear() {
        rows.clear(); byId.clear(); buffers.clear(); pending.clear(); controls.clear(); confirmed.clear(); display.clear();
        AxiomNativeHistoryFiles.disconnect();
        actual = null; revision = -1; rawRevision = -1; navigating = false;
    }

    void disconnect() {
        var buffer = actual;
        var retained = new IdentityHashMap<HistoryEntry<?>, AxiomNativeHistoryFiles.Binding>();
        rows.values().forEach(row -> retained.put(row.entry, row.binding));
        clear();
        // Axiom may reuse the same native buffer when reopening the same world.
        if (buffer != null) AxiomNativeHistoryFiles.retain(buffer, retained);
    }

    private void adopt() {
        AxiomDispatcherPendingAccess.lumi$applyPendingHistory();
        var buffer = AxiomDispatcherPendingAccess.lumi$history();
        if (actual == buffer) return;
        for (var row : rows.values()) AxiomNativeHistoryFiles.forget(row.entry);
        rows.clear(); byId.clear(); buffers.clear(); pending.clear(); confirmed.clear();
        actual = buffer; revision = -1; rawRevision = -1;
        for (var linked : AxiomNativeHistoryFiles.take(buffer).entrySet()) {
            @SuppressWarnings("unchecked") var entry = (HistoryEntry<BlockOrBiomeBuffer>) linked.getKey();
            int index = linked.getValue().position();
            if (index >= 0 && index < buffer.getSize() && buffer.getHistoryEntry(index) == entry) add(new Row(entry, linked.getValue()));
        }
    }

    private void add(Row row) {
        rows.put(row.binding.position(), row); byId.put(row.binding.entry(), row);
        AxiomNativeHistoryFiles.register(row.entry, row.binding);
        while (rows.size() > MAX_ENTRIES) forget(rows.firstEntry().getValue());
    }

    private void forget(Row row) {
        rows.remove(row.binding.position());
        if (byId.remove(row.binding.entry(), row)) pending.remove(row.binding.entry());
        buffers.remove(row.entry.forwards(), row.binding); AxiomNativeHistoryFiles.forget(row.entry);
    }

    private void addGroups(List<WorldActionView.Entry> groups, UUID epoch) {
        for (var group : groups) {
            Row row = null;
            for (UUID id : group.axiomEntries()) {
                var candidate = byId.get(id);
                if (candidate != null && candidate.binding.epoch().equals(epoch)) row = candidate;
            }
            if (row != null) { display.push(row.entry); controls.put(row.entry, row.binding.entry()); }
        }
    }

    private void addPending(boolean applied) {
        for (var entry : pending.entrySet()) if (entry.getValue() == applied) {
            var row = byId.get(entry.getKey());
            if (row != null) { display.push(row.entry); controls.put(row.entry, entry.getKey()); }
        }
    }

    private void syncRawCursor(WorldActionViewPayload payload) {
        Set<UUID> applied = new HashSet<>(), undone = new HashSet<>();
        payload.view().undo().forEach(group -> applied.addAll(group.axiomEntries()));
        payload.view().redo().forEach(group -> undone.addAll(group.axiomEntries()));
        int wanted = Math.min(actual.getPosition(), actual.getSize() - 1);
        for (var row : rows.values()) if (applied.contains(row.binding.entry()) || Boolean.TRUE.equals(pending.get(row.binding.entry())))
            wanted = Math.max(wanted, row.binding.position());
        while (wanted >= 0 && rows.containsKey(wanted) && undone.contains(rows.get(wanted).binding.entry())) wanted--;
        if (wanted != actual.getPosition()) {
            actual.unsafeSetPosition(wanted);
            if (com.moulberry.axiom.world_modification.Dispatcher.historyIdentifier != null)
                HistoryIO.setPosition(AxiomDispatcherPendingAccess.lumi$historyPath(), wanted, actual.getSize());
        }
    }

    private void syncLocalTools(WorldActionViewPayload payload) {
        Map<UUID, Boolean> next = new HashMap<>();
        payload.view().undo().forEach(group -> group.axiomEntries().forEach(id -> next.put(id, true)));
        payload.view().redo().forEach(group -> group.axiomEntries().forEach(id -> next.put(id, false)));
        next.forEach((id, applied) -> {
            var before = confirmed.get(id);
            var row = byId.get(id);
            if (before == null || before.equals(applied) || row == null) return;
            var callback = row.entry.additionalUndoOperation();
            // Entity callbacks mutate the world and may consume their lists; the server already replayed those.
            if (!applied && (callback instanceof ModellingAdditionalUndoOperation || callback instanceof CreatePathAdditionalUndoOperation))
                callback.perform();
            else if (applied && callback instanceof ModellingAdditionalUndoOperation
                    && com.moulberry.axiom.tools.ToolManager.isToolActive()
                    && com.moulberry.axiom.tools.ToolManager.getCurrentTool() instanceof com.moulberry.axiom.tools.modelling.ModellingTool tool)
                tool.reset();
        });
        confirmed.clear(); confirmed.putAll(next);
    }

    private static boolean localOnly(HistoryEntry<?> entry) {
        return entry.forwards() instanceof DummyBuffer && entry.backwards() instanceof DummyBuffer
                && (entry.additionalUndoOperation() instanceof ModellingAdditionalUndoOperation
                    || entry.additionalUndoOperation() instanceof CreatePathAdditionalUndoOperation);
    }

    private static void unavailable() {
        var player = Minecraft.getInstance().player;
        if (player != null) MinecraftPlayerMessages.chat(player, Component.translatable("lumi.axiom.entry_unavailable"));
    }

    private record Row(HistoryEntry<BlockOrBiomeBuffer> entry, AxiomNativeHistoryFiles.Binding binding) { }
}
