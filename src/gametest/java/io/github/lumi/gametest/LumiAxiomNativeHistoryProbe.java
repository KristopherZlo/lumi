package io.github.lumi.gametest;

import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.render.regions.ChunkedBooleanRegion;
import com.moulberry.axiom.world_modification.BlockBuffer;
import com.moulberry.axiom.world_modification.HistoryBuffer;
import com.moulberry.axiom.world_modification.HistoryEntry;
import io.github.lumi.LumiMod;
import io.github.lumi.client.integration.AxiomClientWorkBarrier;
import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.LiveActionOperation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.minecraft.world.DimensionFreeze;
import io.github.lumi.minecraft.world.LiveBlockWorldAccess;
import io.github.lumi.minecraft.world.MinecraftLiveBlockWorldAccess;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Disposable P0 fixture: real native buffers/cursor, an isolated Lumi journal, no production mode switch. */
final class LumiAxiomNativeHistoryProbe {
    private final ClientGameTestContext context;
    private final TestSingleplayerContext world;
    private final LiveActionJournal journal = new LiveActionJournal();
    private final HistoryBuffer<BlockBuffer> history = new HistoryBuffer<>();
    private final Map<HistoryEntry<BlockBuffer>, UUID> links = new IdentityHashMap<>();
    private final UUID actor = UUID.randomUUID();
    private FabricDimensionRuntime runtime;
    private MinecraftLiveBlockWorldAccess blocks;
    private LiveBlockWorldAccess admitted;
    private BlockPos manual, nativeStart;
    private int nativeApplies;

    LumiAxiomNativeHistoryProbe(ClientGameTestContext context, TestSingleplayerContext world) {
        this.context = context;
        this.world = world;
    }

    void run(LumiBehaviorReport report) {
        new LumiUiTestDriver(context).completeOnboardingIfShown();
        context.waitFor(client -> !AxiomClientWorkBarrier.isPaused());
        var player = world.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().getFirst());
        DimensionFreeze.Lease lease = server(() -> {
            runtime = LumiMod.serverRuntime().find(player.level()).orElseThrow();
            require(!runtime.operations().hasActiveOperation(), "Probe requires an idle dimension");
            manual = player.blockPosition().offset(2, 1, 0);
            nativeStart = manual.east();
            for (var pos : List.of(manual, nativeStart, nativeStart.offset(16, 0, 0))) {
                runtime.level().getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            }
            blocks = new MinecraftLiveBlockWorldAccess(runtime.level(), runtime.freeze());
            // The fixture owns the freeze directly; it does not start a client work-barrier round trip.
            admitted = new LiveBlockWorldAccess() {
                public void requirePrepared(BlockSnapshot state) throws java.io.IOException { blocks.requirePrepared(state); }
                public BlockSnapshot read(BlockPosition pos) throws java.io.IOException { return blocks.read(pos); }
                public void write(BlockPosition pos, BlockSnapshot state) throws java.io.IOException { blocks.write(pos, state); }
            };
            var held = runtime.freeze().acquire();
            runtime.freeze().runAuthorized(() -> {
                runtime.level().setBlockAndUpdate(manual, Blocks.AIR.defaultBlockState());
                runtime.level().setBlockAndUpdate(nativeStart, Blocks.STONE.defaultBlockState());
                runtime.level().setBlockAndUpdate(nativeStart.offset(16, 0, 0), Blocks.STONE.defaultBlockState());
            });
            return held;
        });
        try {
            manual("stone", manual);
            var forward = buffer("gold_block");
            var backward = buffer("stone");
            UUID action = server(() -> {
                UUID id = journal.begin(actor);
                var positions = List.of(nativeStart, nativeStart.offset(16, 0, 0));
                var before = positions.stream().map(this::read).toList();
                apply(forward);
                for (int i = 0; i < positions.size(); i++) {
                    var after = read(positions.get(i));
                    blocks.prepare(before.get(i)); blocks.prepare(after);
                    journal.record(id, position(positions.get(i)), before.get(i), after);
                }
                journal.close(id);
                return id;
            });
            var entry = new HistoryEntry<>(forward, backward, nativeStart, "Native P0", HistoryEntry.MODIFIER_CAN_BE_UNDONE_INGAME);
            context.runOnClient(client -> { history.push(entry); links.put(entry, action); });
            nativeStep(true);
            lumiStep(true);
            require(server(() -> read(manual).equals(state("air"))), "Native then Lumi Undo repeated the wall edit");
            lumiStep(false); nativeStep(false);
            lumiStep(true);
            require(context.computeOnClient(client -> history.undo(0) == null), "Lumi Undo left a duplicate native step");
            lumiStep(true); lumiStep(false); lumiStep(false);
            require(context.computeOnClient(client -> history.getPosition() == 0 && history.getHistoryEntry(0) == entry),
                    "Lumi Redo lost native entry identity");
            manual("diamond_block", nativeStart);
            var beforeRefusal = server(journal::snapshotIndex);
            int applies = nativeApplies;
            try { nativeStep(true); throw new AssertionError("Overlapping native Undo was admitted"); }
            catch (IllegalStateException expected) { }
            require(server(journal::snapshotIndex).equals(beforeRefusal) && nativeApplies == applies
                    && context.computeOnClient(client -> history.getPosition() == 0), "Refusal changed a cursor or the world");
            lumiStep(true);
            manual("iron_block", manual);
            nativeStep(true);
            require(server(() -> read(manual).equals(state("iron_block"))), "Selective native Undo overwrote a disjoint newer edit");
            lumiStep(true); lumiStep(true);
            lumiStep(false); lumiStep(false); nativeStep(false);
            report.event("gate", "axiom_native_cursor_probe", "succeeded", 0, 0,
                    "native two-section apply; both Undo/Redo orders; identity; selective Undo; atomic overlap refusal; isolated fixture only");
        } finally {
            server(() -> { journal.clear(); lease.release(); return null; });
        }
    }

    private void nativeStep(boolean undo) {
        var entry = context.computeOnClient(client -> history.getHistoryEntry(history.getPosition() + (undo ? 0 : 1)));
        UUID id = context.computeOnClient(client -> links.get(entry));
        var direction = undo ? LiveActionJournal.Direction.UNDO : LiveActionJournal.Direction.REDO;
        var plan = server(() -> {
            var selected = journal.prepareSelected(actor, id, direction).orElseThrow();
            for (var expected : selected.expected().entrySet())
                require(blocks.read(expected.getKey()).equals(expected.getValue()), "Native preflight differs");
            return selected;
        });
        server(() -> {
            apply(undo ? entry.backwards() : entry.forwards());
            for (var replacement : plan.replacement().entrySet())
                require(blocks.read(replacement.getKey()).equals(replacement.getValue()), "Native result differs");
            journal.completeSelected(plan);
            return null;
        });
        context.runOnClient(client -> require((undo ? history.undo(0) : history.redo(0)) == entry, "Native cursor advanced another entry"));
    }

    private void lumiStep(boolean undo) {
        var direction = undo ? LiveActionJournal.Direction.UNDO : LiveActionJournal.Direction.REDO;
        UUID id = server(() -> {
            var plan = (undo ? journal.prepareUndo(actor) : journal.prepareRedo(actor)).orElseThrow();
            var operation = new LiveActionOperation(journal, actor, direction, admitted);
            operation.advance(Long.MAX_VALUE);
            require(operation.terminalState() == MutationTerminalState.SUCCEEDED, "Lumi step failed: " + operation.failure());
            return plan.actionId();
        });
        context.runOnClient(client -> {
            for (int i = 0; i < history.getSize(); i++) if (id.equals(links.get(history.getHistoryEntry(i)))) {
                int target = undo ? i - 1 : i;
                require(history.getPosition() == target || history.getPosition() == (undo ? i : i - 1), "Native cursor cannot represent Lumi step");
                history.unsafeSetPosition(target);
            }
        });
    }

    private void manual(String block, BlockPos pos) {
        server(() -> {
            var before = read(pos); var after = state(block);
            blocks.prepare(before); blocks.prepare(after);
            UUID id = journal.begin(actor);
            blocks.write(position(pos), after);
            journal.record(id, position(pos), before, after);
            journal.close(id);
            return null;
        });
    }

    private void apply(BlockBuffer buffer) {
        runtime.freeze().runAuthorized(() -> AxiomServerboundSetBuffer.applyBlockBufferServer(
                buffer, runtime.level(), new ChunkedBooleanRegion(), null));
        nativeApplies++;
    }

    private BlockBuffer buffer(String block) {
        return context.computeOnClient(client -> {
            var result = new BlockBuffer();
            var nativeState = block.equals("stone") ? Blocks.STONE.defaultBlockState() : Blocks.GOLD_BLOCK.defaultBlockState();
            for (var pos : List.of(nativeStart, nativeStart.offset(16, 0, 0))) result.set(pos.getX(), pos.getY(), pos.getZ(), nativeState);
            return result;
        });
    }

    private BlockSnapshot read(BlockPos pos) {
        try { return blocks.read(position(pos)); }
        catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
    }

    private <T> T server(Callable<T> work) {
        return world.getServer().computeOnServer(s -> {
            try { return work.call(); }
            catch (RuntimeException failed) { throw failed; }
            catch (Exception failed) { throw new AssertionError("Native probe failed", failed); }
        });
    }

    private static BlockSnapshot state(String block) { return new BlockSnapshot("minecraft:" + block, Optional.empty()); }
    private static BlockPosition position(BlockPos pos) { return new BlockPosition(pos.getX(), pos.getY(), pos.getZ()); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
