package io.github.luma.client.diagnostics;

record ClientGpuMetrics(
        String provider,
        boolean available,
        double utilizationPercent,
        long memoryUsedMiB,
        long memoryTotalMiB,
        String detail
) {

    static ClientGpuMetrics unavailable(String provider, String detail) {
        return new ClientGpuMetrics(provider, false, -1.0D, -1L, -1L, detail);
    }
}
