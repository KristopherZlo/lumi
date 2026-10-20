package io.github.lumi.mixin.client;

import com.moulberry.axiom.world_modification.BlockOrBiomeBuffer;
import com.moulberry.axiom.world_modification.Dispatcher;
import com.moulberry.axiom.world_modification.HistoryBuffer;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Access only the transient queues verified in the pinned Axiom implementations. */
@Mixin(value = Dispatcher.class, remap = false)
public interface AxiomDispatcherPendingAccess {
    @Accessor("dispatchTasks") static List<?> lumi$dispatchTasks() { throw new AssertionError(); }
    @Accessor("tracers") static List<?> lumi$tracers() { throw new AssertionError(); }
    @Accessor("chunkDataCallbacks") static Long2ObjectMap<?> lumi$chunkCallbacks() { throw new AssertionError(); }
    @Accessor("pendingChunkData") static Long2ObjectMap<?> lumi$pendingChunks() { throw new AssertionError(); }
    @Accessor("chunkDataTime") static Long2LongOpenHashMap lumi$chunkTimes() { throw new AssertionError(); }
    @Accessor("entityDataCallbacks") static Long2ObjectMap<?> lumi$entityCallbacks() { throw new AssertionError(); }
    @Accessor("pendingEntityData") static Long2ObjectMap<?> lumi$pendingEntities() { throw new AssertionError(); }
    @Accessor("entityDataTime") static Long2LongOpenHashMap lumi$entityTimes() { throw new AssertionError(); }
    @Accessor("history") static HistoryBuffer<BlockOrBiomeBuffer> lumi$history() { throw new AssertionError(); }
    @Invoker("applyPendingHistory") static void lumi$applyPendingHistory() { throw new AssertionError(); }
    @Invoker("getHistoryPath") static java.nio.file.Path lumi$historyPath() { throw new AssertionError(); }
    @Invoker("undo") static void lumi$undo(boolean placement, boolean selection, boolean inGame) { throw new AssertionError(); }
    @Invoker("redo") static void lumi$redo(boolean placement, boolean selection) { throw new AssertionError(); }
}
