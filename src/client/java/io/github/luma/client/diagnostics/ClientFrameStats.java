package io.github.luma.client.diagnostics;

record ClientFrameStats(
        int samples,
        double estimatedFps,
        double averageMillis,
        double p95Millis,
        double maxMillis
) {

    static ClientFrameStats empty() {
        return new ClientFrameStats(0, 0.0D, 0.0D, 0.0D, 0.0D);
    }
}
