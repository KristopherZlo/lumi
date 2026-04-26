package io.github.luma.ui.controller;

import io.github.luma.domain.model.VariantMergePlan;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

final class MergePreviewCache {

    private final Map<MergePreviewKey, CompletableFuture<VariantMergePlan>> previews = new ConcurrentHashMap<>();
    private final Executor executor;

    MergePreviewCache() {
        this(ForkJoinPool.commonPool());
    }

    MergePreviewCache(Executor executor) {
        this.executor = executor;
    }

    MergePreviewStatus request(MergePreviewKey key, PreviewLoader loader) {
        CompletableFuture<VariantMergePlan> future = this.previews.computeIfAbsent(key, ignored -> CompletableFuture.supplyAsync(() -> {
            try {
                return loader.load(key);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, this.executor));

        if (!future.isDone()) {
            return MergePreviewStatus.pending();
        }

        try {
            return MergePreviewStatus.ready(future.join());
        } catch (CompletionException exception) {
            return MergePreviewStatus.failed(ShareScreenController.describeFailure(exception));
        }
    }

    void invalidate(MergePreviewKey key) {
        this.previews.remove(key);
    }

    void clear() {
        this.previews.clear();
    }

    @FunctionalInterface
    interface PreviewLoader {

        VariantMergePlan load(MergePreviewKey key) throws Exception;
    }
}
