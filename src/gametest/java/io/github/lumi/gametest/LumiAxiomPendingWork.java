package io.github.lumi.gametest;

import com.moulberry.axiom.render.regions.ChunkedBlockRegion;
import com.moulberry.axiom.tools.Tool;
import com.moulberry.axiom.tools.ToolManager;
import com.moulberry.axiom.tools.modelling.ModellingTool;
import com.moulberry.axiom.world_modification.BlockBuffer;
import com.moulberry.axiom.world_modification.BlockOrBiomeBuffer;
import com.moulberry.axiom.world_modification.ChunkDataConsumer;
import com.moulberry.axiom.world_modification.EntityDataConsumer;
import com.moulberry.axiom.world_modification.HistoryEntry;
import com.moulberry.axiom.UserAction;
import com.moulberry.axiom.world_modification.DispatchTask;
import com.moulberry.axiom.world_modification.Dispatcher;
import io.github.lumi.client.integration.AxiomClientWorkBarrier;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.mixin.client.AxiomDispatcherPendingAccess;
import io.github.lumi.network.BuilderToolBarrierPayload.State;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Holds a real native calculation past cancellation and injects its stale queue tail. */
@SuppressWarnings({"UnstableApiUsage", "unchecked"})
final class LumiAxiomPendingWork implements AutoCloseable {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final CountDownLatch lateStarted = new CountDownLatch(1);
    private final CountDownLatch lateRelease = new CountDownLatch(1);
    private final CountDownLatch lateFinished = new CountDownLatch(1);
    private final AtomicReference<LumiAxiomPendingWork> tickFixture = new AtomicReference<>(this);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger callbacks = new AtomicInteger();
    private final long chunkRequest = UUID.randomUUID().getMostSignificantBits();
    private final long entityRequest = UUID.randomUUID().getMostSignificantBits();
    private final ClientGameTestContext context;
    private ChunkedBlockRegion oldResult;
    private Future<?> calculation;
    private Future<?> lateCalculation;
    private ModellingTool model;
    private Field pending;
    private Thread controller;

    LumiAxiomPendingWork(ClientGameTestContext context, FabricDimensionRuntime runtime, BlockPos ghost) {
        this.context = context;
        context.runOnClient(client -> {
            try {
                Dispatcher.push(new HistoryEntry<BlockOrBiomeBuffer>(new BlockBuffer(), new BlockBuffer(),
                        ghost, "Lumi stale history fixture", 0));
                if (AxiomDispatcherPendingAccess.lumi$history().getSize() == 0) {
                    throw new AssertionError("Native Axiom history fixture was not accepted");
                }
                ((Long2ObjectMap<ChunkDataConsumer>) (Long2ObjectMap<?>) AxiomDispatcherPendingAccess.lumi$chunkCallbacks())
                        .put(chunkRequest, (entities, sections) -> callbacks.incrementAndGet());
                ((Long2ObjectMap<EntityDataConsumer>) (Long2ObjectMap<?>) AxiomDispatcherPendingAccess.lumi$entityCallbacks())
                        .put(entityRequest, entities -> callbacks.incrementAndGet());
                model = ToolManager.getTools().stream().filter(ModellingTool.class::isInstance)
                        .map(ModellingTool.class::cast).findFirst().orElseThrow();
                pending = ModellingTool.class.getDeclaredField("pendingCalculation");
                pending.setAccessible(true);
                if (pending.get(model) != null) throw new AssertionError("Model fixture is already calculating");
                oldResult = new ChunkedBlockRegion();
                oldResult.addBlockWithoutDirty(ghost.getX(), ghost.getY(), ghost.getZ(), Blocks.DIAMOND_BLOCK.defaultBlockState());
                calculation = Tool.sharedSingleThreadExecutor.submit(() -> {
                    started.countDown();
                    try {
                        while (release.getCount() != 0) {
                            try { release.await(); } catch (InterruptedException ignored) { /* Deliberately ignores cancellation. */ }
                        }
                        return oldResult;
                    } finally {
                        finished.countDown();
                    }
                });
                pending.set(model, calculation);
                var stale = new BlockBuffer();
                stale.set(ghost.getX(), ghost.getY(), ghost.getZ(), Blocks.DIAMOND_BLOCK.defaultBlockState());
                DispatchTask tail = DispatchTask.blockOrBiome(client.level.dimension(), stale);
                Field instance = AxiomClientWorkBarrier.class.getDeclaredField("INSTANCE");
                instance.setAccessible(true);
                Object barrier = instance.get(null);
                Field acknowledgement = AxiomClientWorkBarrier.class.getDeclaredField("acknowledgement");
                acknowledgement.setAccessible(true);
                var fixtureReference = tickFixture;
                ClientTickEvents.END_CLIENT_TICK.register(ignored -> {
                    var fixture = fixtureReference.get();
                    if (fixture != null) fixture.installLateWork(barrier, acknowledgement, tail);
                });
                controller = Thread.ofPlatform().daemon(true).name("Lumi-Axiom-stale-result-fixture").unstarted(() -> {
                    try {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        while (!AxiomClientWorkBarrier.isPaused() || !calculation.isCancelled()) {
                            if (System.nanoTime() >= deadline) throw new AssertionError("Native Axiom calculation was not cancelled");
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                        }
                        var queued = new CountDownLatch(1);
                        Minecraft.getInstance().execute(() -> {
                            try {
                                Dispatcher.apply(stale, false);
                                ((List<DispatchTask>) (List<?>) AxiomDispatcherPendingAccess.lumi$dispatchTasks()).add(tail);
                            } finally { queued.countDown(); }
                        });
                        await(queued, "Stale native queue tail was not injected");
                        if (release.await(200, TimeUnit.MILLISECONDS)) throw new AssertionError("Fixture released too early");
                        if (!runtime.freeze().isFrozen() || !runtime.operations().hasActiveOperation()) {
                            throw new AssertionError("World replacement ended before the cancelled worker actually stopped");
                        }
                    } catch (Throwable failed) {
                        failure.set(failed);
                    } finally {
                        release.countDown();
                    }
                });
            } catch (ReflectiveOperationException failed) {
                throw new AssertionError("Cannot install native Axiom calculation fixture", failed);
            }
        });
        await(started, "Native Axiom calculation did not start");
        controller.start();
    }

    private void installLateWork(Object barrier, Field acknowledgement, DispatchTask tail) {
        try {
            if (lateCalculation != null || finished.getCount() != 0 || !AxiomClientWorkBarrier.isPaused()
                    || acknowledgement.get(barrier) != State.READY) return;
            // A queued input can start another calculation after READY, before RESUME is handled.
            lateCalculation = Tool.sharedSingleThreadExecutor.submit(() -> {
                lateStarted.countDown();
                try {
                    while (lateRelease.getCount() != 0) {
                        try { lateRelease.await(); } catch (InterruptedException ignored) { }
                    }
                    return oldResult;
                } finally { lateFinished.countDown(); }
            });
            pending.set(model, lateCalculation);
            Thread.ofPlatform().daemon(true).name("Lumi-Axiom-resume-fixture").start(() -> {
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!lateCalculation.isCancelled()) {
                        if (System.nanoTime() >= deadline) throw new AssertionError("RESUME did not cancel late Axiom work");
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    }
                    var queued = new CountDownLatch(1);
                    Minecraft.getInstance().execute(() -> {
                        ((List<DispatchTask>) (List<?>) AxiomDispatcherPendingAccess.lumi$dispatchTasks()).add(tail);
                        queued.countDown();
                    });
                    await(queued, "Late resume tail was not injected");
                    if (lateRelease.await(200, TimeUnit.MILLISECONDS) || !AxiomClientWorkBarrier.isPaused()) {
                        throw new AssertionError("Axiom resumed before its late calculation stopped");
                    }
                } catch (Throwable failed) {
                    failure.compareAndSet(null, failed);
                } finally { lateRelease.countDown(); }
            });
        } catch (ReflectiveOperationException failed) {
            throw new AssertionError("Cannot install late native Axiom work", failed);
        }
    }

    void assertDrained() {
        context.waitFor(client -> failure.get() != null || finished.getCount() == 0
                && lateStarted.getCount() == 0 && lateFinished.getCount() == 0);
        if (failure.get() != null) throw new AssertionError("Axiom stale-result fixture failed", failure.get());
        context.waitFor(client -> !AxiomClientWorkBarrier.isPaused());
        context.runOnClient(client -> {
            if (!AxiomDispatcherPendingAccess.lumi$dispatchTasks().isEmpty()) {
                throw new AssertionError("Stale native dispatch survived replacement admission");
            }
            Dispatcher.finishRequestChunkData(chunkRequest, false, new Long2ObjectOpenHashMap<>(), new Long2ObjectOpenHashMap<>());
            Dispatcher.finishRequestChunkData(chunkRequest, true, new Long2ObjectOpenHashMap<>(), new Long2ObjectOpenHashMap<>());
            Dispatcher.finishRequestEntityData(entityRequest, false, Map.of());
            Dispatcher.finishRequestEntityData(entityRequest, true, Map.of());
            if (callbacks.get() != 0 || AxiomDispatcherPendingAccess.lumi$history().getSize() != 0) {
                throw new AssertionError("Stale native callbacks/history survived replacement");
            }
            Dispatcher.callAction(UserAction.UNDO, null);
            Dispatcher.callAction(UserAction.REDO, null);
        });
        context.waitTicks(3);
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError(message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }

    @Override public void close() {
        tickFixture.set(null);
        release.countDown();
        lateRelease.countDown();
        await(finished, "Native fixture worker did not finish during cleanup");
        if (lateCalculation != null) await(lateFinished, "Late native fixture worker did not finish during cleanup");
        context.runOnClient(client -> oldResult.clear());
    }
}
