package io.github.luma.client.diagnostics;

import java.util.Arrays;

final class ClientFrameTimeWindow {

    private final long[] frameNanos;
    private int size;
    private int cursor;

    ClientFrameTimeWindow(int capacity) {
        this.frameNanos = new long[Math.max(1, capacity)];
    }

    void record(long nanos) {
        if (nanos <= 0L) {
            return;
        }
        this.frameNanos[this.cursor] = nanos;
        this.cursor = (this.cursor + 1) % this.frameNanos.length;
        if (this.size < this.frameNanos.length) {
            this.size += 1;
        }
    }

    ClientFrameStats snapshotAndReset() {
        if (this.size <= 0) {
            return ClientFrameStats.empty();
        }

        long[] values = Arrays.copyOf(this.frameNanos, this.size);
        Arrays.sort(values);
        long total = 0L;
        long max = values[values.length - 1];
        for (long value : values) {
            total += value;
        }
        double averageNanos = (double) total / values.length;
        long p95 = values[Math.min(values.length - 1, (int) Math.ceil(values.length * 0.95D) - 1)];
        this.size = 0;
        this.cursor = 0;
        Arrays.fill(this.frameNanos, 0L);
        return new ClientFrameStats(
                values.length,
                averageNanos <= 0.0D ? 0.0D : 1_000_000_000.0D / averageNanos,
                averageNanos / 1_000_000.0D,
                p95 / 1_000_000.0D,
                max / 1_000_000.0D
        );
    }
}
