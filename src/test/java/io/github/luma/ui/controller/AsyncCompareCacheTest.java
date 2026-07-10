package io.github.luma.ui.controller;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import io.github.luma.domain.model.VersionDiff;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncCompareCacheTest {

    @BeforeEach
    void clearCacheBeforeTest() {
        AsyncCompareCache.getInstance().clear();
    }

    @AfterEach
    void clearCacheAfterTest() {
        AsyncCompareCache.getInstance().clear();
    }

    @Test
    void requestReturnsLoadingBeforeBackgroundResultThenReady() throws Exception {
        AsyncCompareCache cache = AsyncCompareCache.getInstance();
        CompareRequestKey key = new CompareRequestKey("async-test-loading", "v0001", "v0002");
        CountDownLatch release = new CountDownLatch(1);

        AsyncCompareCache.CompareResultState first = cache.request(key, () -> {
            release.await(1, TimeUnit.SECONDS);
            return new AsyncCompareCache.CompareResult(new VersionDiff("v0001", "v0002", List.of(), 0), List.of());
        }, true);

        assertEquals(AsyncCompareCache.Status.LOADING, first.status());
        release.countDown();

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

        CountDownLatch release = new CountDownLatch(1);
        AsyncCompareCache.CompareResultState refreshed = cache.request(key, () -> {
            int marker = calls.incrementAndGet();
            release.await(1, TimeUnit.SECONDS);
            return result(marker);
        }, true);

        assertEquals(AsyncCompareCache.Status.LOADING, refreshed.status());
        release.countDown();
        awaitReady(cache, key);
        assertEquals(2, calls.get());
    }

    @Test
    void clearingCacheInterruptsRunningCompareTask() throws Exception {
        AsyncCompareCache cache = AsyncCompareCache.getInstance();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        cache.request(key(50), () -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw exception;
            }
            return result(50);
        }, true);

        assertTrue(started.await(1, TimeUnit.SECONDS));
        cache.clear();
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
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

    @Test
    void oldestRequestsAreEvictedWhenByteLimitIsReached() throws Exception {
        AsyncCompareCache cache = new AsyncCompareCache(48L * 1024L);
        CompareRequestKey firstKey = key(0);

        cache.request(firstKey, () -> result(0), true);
        awaitReady(cache, firstKey);
        for (int index = 1; index <= 2; index++) {
            int marker = index;
            CompareRequestKey requestKey = key(marker);
            cache.request(requestKey, () -> resultWithBlocks(marker, 50), true);
            awaitReady(cache, requestKey);
        }

        assertTrue(cache.cachedBytesForTest() <= 48L * 1024L);

        cache.request(firstKey, () -> result(99), false);
        AsyncCompareCache.CompareResultState reloaded = awaitReady(cache, firstKey);

        assertEquals("v0099", reloaded.result().diff().leftVersionId());
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

    private static AsyncCompareCache.CompareResult resultWithBlocks(int marker, int count) {
        List<DiffBlockEntry> blocks = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            blocks.add(new DiffBlockEntry(
                    new BlockPoint(index, 64, marker),
                    "minecraft:stone",
                    "minecraft:glass",
                    ChangeType.CHANGED
            ));
        }
        return new AsyncCompareCache.CompareResult(
                new VersionDiff("v%04d".formatted(marker), "v0002", blocks, 1),
                List.of()
        );
    }

    private static CompareRequestKey key(int marker) {
        return new CompareRequestKey("async-test-eviction-" + marker, "v0001", "v0002");
    }
}
