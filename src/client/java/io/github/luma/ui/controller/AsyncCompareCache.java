package io.github.luma.ui.controller;

import io.github.luma.domain.model.MaterialDeltaEntry;
import io.github.luma.domain.model.VersionDiff;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Shared client cache for expensive compare requests.
 */
public final class AsyncCompareCache {

    private static final int DEFAULT_MAX_REQUESTS = 24;
    private static final AsyncCompareCache INSTANCE = new AsyncCompareCache();

    private final ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "lumi-compare-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final BoundedRequestCache requests;

    private AsyncCompareCache() {
        this.requests = new BoundedRequestCache(DEFAULT_MAX_REQUESTS);
    }

    public static AsyncCompareCache getInstance() {
        return INSTANCE;
    }

    public CompareResultState request(CompareRequestKey key, CompareTask task, boolean refresh) {
        if (key == null || !key.valid()) {
            return CompareResultState.ready(new CompareResult(null, List.of()));
        }
        if (refresh) {
            this.requests.remove(key).ifPresent(AsyncCompareCache::cancelIfRunning);
        }
        CompletableFuture<CompareResult> future = this.requests.getOrCreate(key, () ->
                CompletableFuture.supplyAsync(() -> this.runTask(task), this.executor)
        );
        if (!future.isDone()) {
            return CompareResultState.loading();
        }
        try {
            return CompareResultState.ready(future.join());
        } catch (CompletionException exception) {
            return CompareResultState.failed(exception.getCause() == null ? exception : exception.getCause());
        }
    }

    public void clear() {
        this.requests.clear();
    }

    int cachedRequestCountForTest() {
        return this.requests.size();
    }

    private static void cancelIfRunning(CompletableFuture<CompareResult> future) {
        if (!future.isDone()) {
            future.cancel(true);
        }
    }

    private CompareResult runTask(CompareTask task) {
        try {
            return task.run();
        } catch (Exception exception) {
            throw new CompletionException(exception);
        }
    }

    private static final class BoundedRequestCache {

        private final int maxRequests;
        private final LinkedHashMap<CompareRequestKey, CompletableFuture<CompareResult>> requests =
                new LinkedHashMap<>(16, 0.75F, true);

        private BoundedRequestCache(int maxRequests) {
            this.maxRequests = Math.max(1, maxRequests);
        }

        private synchronized CompletableFuture<CompareResult> getOrCreate(
                CompareRequestKey key,
                Supplier<CompletableFuture<CompareResult>> supplier
        ) {
            CompletableFuture<CompareResult> existing = this.requests.get(key);
            if (existing != null) {
                return existing;
            }
            CompletableFuture<CompareResult> future = supplier.get();
            this.requests.put(key, future);
            this.trimToMax();
            return future;
        }

        private synchronized Optional<CompletableFuture<CompareResult>> remove(CompareRequestKey key) {
            return Optional.ofNullable(this.requests.remove(key));
        }

        private synchronized void clear() {
            List<CompletableFuture<CompareResult>> futures = new ArrayList<>(this.requests.values());
            this.requests.clear();
            futures.forEach(AsyncCompareCache::cancelIfRunning);
        }

        private synchronized int size() {
            return this.requests.size();
        }

        private void trimToMax() {
            Iterator<Map.Entry<CompareRequestKey, CompletableFuture<CompareResult>>> iterator =
                    this.requests.entrySet().iterator();
            while (this.requests.size() > this.maxRequests && iterator.hasNext()) {
                Map.Entry<CompareRequestKey, CompletableFuture<CompareResult>> eldest = iterator.next();
                iterator.remove();
                cancelIfRunning(eldest.getValue());
            }
        }
    }

    @FunctionalInterface
    public interface CompareTask {

        CompareResult run() throws Exception;
    }

    public record CompareResult(VersionDiff diff, List<MaterialDeltaEntry> materialDelta) {

        public CompareResult {
            materialDelta = materialDelta == null ? List.of() : List.copyOf(materialDelta);
        }
    }

    public record CompareResultState(Status status, CompareResult result, Throwable failure) {

        private static CompareResultState loading() {
            return new CompareResultState(Status.LOADING, null, null);
        }

        private static CompareResultState ready(CompareResult result) {
            return new CompareResultState(Status.READY, result, null);
        }

        private static CompareResultState failed(Throwable failure) {
            return new CompareResultState(Status.FAILED, null, failure);
        }
    }

    public enum Status {
        LOADING,
        READY,
        FAILED
    }
}
