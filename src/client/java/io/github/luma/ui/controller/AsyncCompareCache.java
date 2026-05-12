package io.github.luma.ui.controller;

import io.github.luma.domain.model.MaterialDeltaEntry;
import io.github.luma.domain.model.VersionDiff;
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

    private static final long DEFAULT_MAX_BYTES = 64L * 1024L * 1024L;
    private static final long PENDING_REQUEST_BYTES = 64L * 1024L;
    private static final long MIN_RESULT_BYTES = 16L * 1024L;
    private static final long BASE_RESULT_BYTES = 4L * 1024L;
    private static final long DIFF_BLOCK_BYTES = 384L;
    private static final long ENTITY_DIFF_BYTES = 512L;
    private static final long MATERIAL_DELTA_BYTES = 96L;
    private static final AsyncCompareCache INSTANCE = new AsyncCompareCache();

    private final ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "lumi-compare-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final BoundedRequestCache requests;

    private AsyncCompareCache() {
        this(DEFAULT_MAX_BYTES);
    }

    AsyncCompareCache(long maxBytes) {
        this.requests = new BoundedRequestCache(maxBytes);
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

    long cachedBytesForTest() {
        return this.requests.bytes();
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

        private final long maxBytes;
        private final LinkedHashMap<CompareRequestKey, CachedRequest> requests =
                new LinkedHashMap<>(16, 0.75F, true);
        private long currentBytes;

        private BoundedRequestCache(long maxBytes) {
            this.maxBytes = Math.max(MIN_RESULT_BYTES, maxBytes);
        }

        private synchronized CompletableFuture<CompareResult> getOrCreate(
                CompareRequestKey key,
                Supplier<CompletableFuture<CompareResult>> supplier
        ) {
            CachedRequest existing = this.requests.get(key);
            if (existing != null) {
                return existing.future();
            }
            CompletableFuture<CompareResult> future = supplier.get();
            CachedRequest request = new CachedRequest(future, PENDING_REQUEST_BYTES);
            this.requests.put(key, request);
            this.currentBytes += request.estimatedBytes();
            future.whenComplete((result, failure) ->
                    this.updateEstimatedBytes(key, request, result == null ? MIN_RESULT_BYTES : result.estimatedBytes())
            );
            this.trimToMax();
            return future;
        }

        private synchronized Optional<CompletableFuture<CompareResult>> remove(CompareRequestKey key) {
            CachedRequest request = this.requests.remove(key);
            if (request == null) {
                return Optional.empty();
            }
            this.currentBytes -= request.estimatedBytes();
            return Optional.of(request.future());
        }

        private synchronized void clear() {
            List<CompletableFuture<CompareResult>> futures = this.requests.values().stream()
                    .map(CachedRequest::future)
                    .toList();
            this.requests.clear();
            this.currentBytes = 0L;
            futures.forEach(AsyncCompareCache::cancelIfRunning);
        }

        private synchronized int size() {
            return this.requests.size();
        }

        private synchronized long bytes() {
            return this.currentBytes;
        }

        private synchronized void updateEstimatedBytes(
                CompareRequestKey key,
                CachedRequest request,
                long estimatedBytes
        ) {
            CachedRequest current = this.requests.get(key);
            if (current != request) {
                return;
            }
            long sanitizedBytes = Math.max(MIN_RESULT_BYTES, estimatedBytes);
            this.currentBytes += sanitizedBytes - request.estimatedBytes();
            this.requests.put(key, new CachedRequest(request.future(), sanitizedBytes));
            this.trimToMax();
        }

        private void trimToMax() {
            Iterator<Map.Entry<CompareRequestKey, CachedRequest>> iterator =
                    this.requests.entrySet().iterator();
            while (this.currentBytes > this.maxBytes && this.requests.size() > 1 && iterator.hasNext()) {
                Map.Entry<CompareRequestKey, CachedRequest> eldest = iterator.next();
                iterator.remove();
                this.currentBytes -= eldest.getValue().estimatedBytes();
                cancelIfRunning(eldest.getValue().future());
            }
        }

        private record CachedRequest(CompletableFuture<CompareResult> future, long estimatedBytes) {
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

        long estimatedBytes() {
            long bytes = BASE_RESULT_BYTES;
            if (this.diff != null) {
                bytes += (long) this.diff.changedEntityCount() * ENTITY_DIFF_BYTES;
                for (var block : this.diff.changedBlocks()) {
                    bytes += DIFF_BLOCK_BYTES
                            + stringBytes(block.leftState())
                            + stringBytes(block.rightState())
                            + stringBytes(block.leftBlockId())
                            + stringBytes(block.rightBlockId());
                }
            }
            bytes += (long) this.materialDelta.size() * MATERIAL_DELTA_BYTES;
            return Math.max(MIN_RESULT_BYTES, bytes);
        }

        private static long stringBytes(String value) {
            return value == null ? 0L : (long) value.length() * 2L;
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
