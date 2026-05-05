package io.github.luma.ui.controller;

import io.github.luma.domain.model.MaterialDeltaEntry;
import io.github.luma.domain.model.VersionDiff;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared client cache for expensive compare requests.
 */
public final class AsyncCompareCache {

    private static final AsyncCompareCache INSTANCE = new AsyncCompareCache();

    private final ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "lumi-compare-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentMap<CompareRequestKey, CompletableFuture<CompareResult>> requests = new ConcurrentHashMap<>();

    private AsyncCompareCache() {
    }

    public static AsyncCompareCache getInstance() {
        return INSTANCE;
    }

    public CompareResultState request(CompareRequestKey key, CompareTask task, boolean refresh) {
        if (key == null || !key.valid()) {
            return CompareResultState.ready(new CompareResult(null, List.of()));
        }
        if (refresh) {
            this.requests.remove(key);
        }
        CompletableFuture<CompareResult> future = this.requests.computeIfAbsent(key, ignored ->
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

    private CompareResult runTask(CompareTask task) {
        try {
            return task.run();
        } catch (Exception exception) {
            throw new CompletionException(exception);
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
