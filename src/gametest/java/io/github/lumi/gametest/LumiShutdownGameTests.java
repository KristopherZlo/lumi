package io.github.lumi.gametest;

import io.github.lumi.minecraft.world.MinecraftChunkDurabilityRetention;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;

/** Reproduces the vanilla stopped executor's inline fallback without stopping the test server. */
public final class LumiShutdownGameTests {
    private static final ThreadLocal<Boolean> CLOSING_WORKER = new ThreadLocal<>();

    public static boolean isClosingWorker() { return Boolean.TRUE.equals(CLOSING_WORKER.get()); }

    @GameTest(maxTicks = 300000)
    public void closingWorkerCannotReleaseTicketsOutsideTheServerQueue(GameTestHelper helper) {
        var level = helper.getLevel();
        var storage = level.getDataStorage().computeIfAbsent(TicketStorage.TYPE);
        var retention = new MinecraftChunkDurabilityRetention(level);
        long packed = ChunkPos.asLong(4098, 4098);
        var test = UUID.randomUUID();
        helper.startSequence()
                .thenWaitUntil(() -> LumiGameTestLease.acquire(helper, test))
                .thenExecute(() -> {
                    retention.retain(4098, 4098);
                    int retained = storage.getTickets(packed).size();
                    helper.assertTrue(retained > 0, "Control chunk has no retained ticket");
                    var failure = new AtomicReference<Throwable>();
                    var worker = new Thread(() -> {
                        CLOSING_WORKER.set(true);
                        try { retention.release(4098, 4098); }
                        catch (Throwable failed) { failure.set(failed); }
                        finally { CLOSING_WORKER.remove(); }
                    }, "Lumi-test-closing-ticket-release");
                    worker.setDaemon(true);
                    worker.start();
                    try { worker.join(5000); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted waiting for ticket worker", interrupted);
                    }
                    helper.assertTrue(!worker.isAlive(), "Ticket worker did not finish");
                    if (failure.get() != null) throw new AssertionError("Ticket worker failed", failure.get());
                    helper.assertValueEqual(retained, storage.getTickets(packed).size(),
                            "Closing executor released a ticket directly on its background caller");
                })
                .thenWaitUntil(() -> helper.assertTrue(storage.getTickets(packed).isEmpty(),
                        "Queued ticket release did not run on the server"))
                .thenExecute(() -> LumiGameTestLease.release(test))
                .thenSucceed();
    }
}
