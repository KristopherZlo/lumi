package io.github.lumi.gametest;

import io.github.lumi.client.integration.AxiomClientWorkBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;

/** The remote worker stays alive until the server owner has inspected its admission freeze. */
final class LumiAxiomRemotePendingWork implements AutoCloseable {
    private final ClientGameTestContext context;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean held = new AtomicBoolean();
    private final LumiAxiomPendingWork fixture;

    LumiAxiomRemotePendingWork(ClientGameTestContext context) {
        this.context = context;
        fixture = new LumiAxiomPendingWork(context, AxiomClientWorkBarrier::isPaused,
                new BlockPos(16, 170, 1), () -> {
                    held.set(true);
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Server did not inspect remote admission");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Remote fixture controller interrupted", interrupted);
                    }
                });
    }

    void awaitHeld() { context.waitFor(client -> held.get(), 2400); }
    void release() { release.countDown(); }
    void assertDrained() { fixture.assertDrained(); }
    @Override public void close() { release(); fixture.close(); }
}
