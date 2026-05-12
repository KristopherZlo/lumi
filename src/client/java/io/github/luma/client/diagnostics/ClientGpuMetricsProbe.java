package io.github.luma.client.diagnostics;

interface ClientGpuMetricsProbe extends AutoCloseable {

    ClientGpuMetrics sample();

    @Override
    default void close() {
    }
}
