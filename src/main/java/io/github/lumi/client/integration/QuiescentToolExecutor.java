package io.github.lumi.client.integration;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts actual task completion, including calculations that ignore Future cancellation. */
public final class QuiescentToolExecutor extends ThreadPoolExecutor {
    private final AtomicInteger pending = new AtomicInteger();

    public QuiescentToolExecutor(ThreadFactory factory) {
        super(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory);
    }

    public boolean isQuiescent() { return pending.get() == 0; }

    @Override public void execute(Runnable task) {
        pending.incrementAndGet();
        try {
            super.execute(task);
        } catch (RuntimeException rejected) {
            pending.decrementAndGet();
            throw rejected;
        }
    }

    @Override protected void afterExecute(Runnable task, Throwable failure) {
        try {
            super.afterExecute(task, failure);
        } finally {
            pending.decrementAndGet();
        }
    }

    @Override public List<Runnable> shutdownNow() {
        List<Runnable> discarded = super.shutdownNow();
        pending.addAndGet(-discarded.size());
        return discarded;
    }
}
