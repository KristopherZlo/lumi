package io.github.luma.ui.controller;

import io.github.luma.domain.model.VariantMergePlan;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MergePreviewCacheTest {

    @Test
    void cachesCompletedPreviewBySelection() {
        MergePreviewCache cache = new MergePreviewCache(Runnable::run);
        MergePreviewKey key = new MergePreviewKey("Tower", "Tower - Shared Roof pass", "roof-pass", "main");
        AtomicInteger calls = new AtomicInteger();

        MergePreviewStatus first = cache.request(key, ignored -> {
            calls.incrementAndGet();
            return plan();
        });
        MergePreviewStatus second = cache.request(key, ignored -> {
            calls.incrementAndGet();
            return plan();
        });

        assertEquals(MergePreviewStatus.State.READY, first.state());
        assertEquals(MergePreviewStatus.State.READY, second.state());
        assertEquals(1, calls.get());
    }

    private static VariantMergePlan plan() {
        return new VariantMergePlan(
                "Tower - Shared Roof pass",
                "roof-pass",
                "v0002",
                "Tower",
                "main",
                "v0001",
                "v0001",
                0,
                0,
                List.of(),
                List.of()
        );
    }
}
