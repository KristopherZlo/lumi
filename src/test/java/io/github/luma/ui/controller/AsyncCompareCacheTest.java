package io.github.luma.ui.controller;

import io.github.luma.domain.model.VersionDiff;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncCompareCacheTest {

    @Test
    void requestReturnsLoadingBeforeBackgroundResultThenReady() throws Exception {
        AsyncCompareCache cache = AsyncCompareCache.getInstance();
        CompareRequestKey key = new CompareRequestKey("async-test-loading", "v0001", "v0002");

        AsyncCompareCache.CompareResultState first = cache.request(key, () ->
                new AsyncCompareCache.CompareResult(new VersionDiff("v0001", "v0002", List.of(), 0), List.of()), true);

        assertEquals(AsyncCompareCache.Status.LOADING, first.status());

        AsyncCompareCache.CompareResultState ready = awaitReady(cache, key);

        assertEquals(AsyncCompareCache.Status.READY, ready.status());
        assertEquals("v0001", ready.result().diff().leftVersionId());
        assertEquals("v0002", ready.result().diff().rightVersionId());
    }

    @Test
    void refreshStartsANewBackgroundTaskForSameKey() throws Exception {
        AsyncCompareCache cache = AsyncCompareCache.getInstance();
        CompareRequestKey key = new CompareRequestKey("async-test-refresh", "v0001", "v0002");
        AtomicInteger calls = new AtomicInteger();

        cache.request(key, () -> result(calls.incrementAndGet()), true);
        awaitReady(cache, key);

        AsyncCompareCache.CompareResultState refreshed = cache.request(key, () -> result(calls.incrementAndGet()), true);

        assertEquals(AsyncCompareCache.Status.LOADING, refreshed.status());
        awaitReady(cache, key);
        assertEquals(2, calls.get());
    }

    @Test
    void invalidKeyIsReadyWithEmptyResult() {
        AsyncCompareCache.CompareResultState state = AsyncCompareCache.getInstance().request(
                new CompareRequestKey("project", "", "v0002"),
                () -> result(1),
                true
        );

        assertEquals(AsyncCompareCache.Status.READY, state.status());
        assertNull(state.result().diff());
        assertTrue(state.result().materialDelta().isEmpty());
    }

    private static AsyncCompareCache.CompareResultState awaitReady(
            AsyncCompareCache cache,
            CompareRequestKey key
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            AsyncCompareCache.CompareResultState state = cache.request(key, () -> result(0), false);
            if (state.status() == AsyncCompareCache.Status.READY) {
                return state;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Compare request did not complete");
    }

    private static AsyncCompareCache.CompareResult result(int marker) {
        return new AsyncCompareCache.CompareResult(
                new VersionDiff("v%04d".formatted(marker), "v0002", List.of(), 0),
                List.of()
        );
    }
}
