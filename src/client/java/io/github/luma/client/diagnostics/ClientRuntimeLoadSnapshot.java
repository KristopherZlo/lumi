package io.github.luma.client.diagnostics;

import java.time.Instant;

record ClientRuntimeLoadSnapshot(
        Instant time,
        String dimension,
        String screen,
        int windowWidth,
        int windowHeight,
        int guiWidth,
        int guiHeight,
        ClientFrameStats frameStats,
        double processCpuLoadPercent,
        double systemCpuLoadPercent,
        double processCpuCores,
        int availableProcessors,
        int liveThreads,
        long heapUsedMiB,
        long heapCommittedMiB,
        long heapMaxMiB,
        long nonHeapUsedMiB,
        long directBufferUsedMiB,
        long mappedBufferUsedMiB,
        long gcCount,
        long gcTimeMillis,
        ClientGpuMetrics gpuMetrics
) {
}
