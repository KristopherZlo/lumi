package io.github.luma.client.diagnostics;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.List;

final class ClientJvmLoadProbe {

    private final com.sun.management.OperatingSystemMXBean operatingSystem;
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final List<BufferPoolMXBean> bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    private long lastProcessCpuTimeNanos = -1L;
    private long lastWallNanos = -1L;

    ClientJvmLoadProbe() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        this.operatingSystem = bean instanceof com.sun.management.OperatingSystemMXBean os ? os : null;
    }

    CpuStats cpuStats(long nowNanos) {
        int processors = Runtime.getRuntime().availableProcessors();
        double processLoad = -1.0D;
        double systemLoad = -1.0D;
        double processCpuCores = -1.0D;
        if (this.operatingSystem != null) {
            processLoad = toPercent(this.operatingSystem.getProcessCpuLoad());
            systemLoad = toPercent(this.operatingSystem.getCpuLoad());
            long processCpuTime = this.operatingSystem.getProcessCpuTime();
            if (this.lastProcessCpuTimeNanos >= 0L && this.lastWallNanos >= 0L && nowNanos > this.lastWallNanos) {
                processCpuCores = (double) (processCpuTime - this.lastProcessCpuTimeNanos)
                        / (double) (nowNanos - this.lastWallNanos);
            }
            this.lastProcessCpuTimeNanos = processCpuTime;
            this.lastWallNanos = nowNanos;
        }
        return new CpuStats(
                processLoad,
                systemLoad,
                processCpuCores,
                processors,
                this.threads.getThreadCount()
        );
    }

    MemoryStats memoryStats() {
        MemoryUsage heap = this.memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = this.memory.getNonHeapMemoryUsage();
        long gcCount = 0L;
        long gcTime = 0L;
        for (GarbageCollectorMXBean collector : this.garbageCollectors) {
            gcCount += Math.max(0L, collector.getCollectionCount());
            gcTime += Math.max(0L, collector.getCollectionTime());
        }
        return new MemoryStats(
                toMiB(heap.getUsed()),
                toMiB(heap.getCommitted()),
                toMiB(heap.getMax()),
                toMiB(nonHeap.getUsed()),
                this.bufferPoolMiB("direct"),
                this.bufferPoolMiB("mapped"),
                gcCount,
                gcTime
        );
    }

    private long bufferPoolMiB(String name) {
        for (BufferPoolMXBean pool : this.bufferPools) {
            if (pool.getName().equalsIgnoreCase(name)) {
                return toMiB(pool.getMemoryUsed());
            }
        }
        return -1L;
    }

    private static double toPercent(double value) {
        return value < 0.0D ? -1.0D : value * 100.0D;
    }

    private static long toMiB(long bytes) {
        return bytes < 0L ? -1L : bytes / (1024L * 1024L);
    }

    record CpuStats(
            double processCpuLoadPercent,
            double systemCpuLoadPercent,
            double processCpuCores,
            int availableProcessors,
            int liveThreads
    ) {
    }

    record MemoryStats(
            long heapUsedMiB,
            long heapCommittedMiB,
            long heapMaxMiB,
            long nonHeapUsedMiB,
            long directBufferUsedMiB,
            long mappedBufferUsedMiB,
            long gcCount,
            long gcTimeMillis
    ) {
    }
}
