package io.github.luma.client.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class NvidiaSmiClientGpuMetricsProbe implements ClientGpuMetricsProbe {

    private static final Duration TIMEOUT = Duration.ofMillis(1500L);

    private boolean commandResolved;
    private String command;

    @Override
    public ClientGpuMetrics sample() {
        Optional<String> resolvedCommand = this.resolveCommand();
        if (resolvedCommand.isEmpty()) {
            return ClientGpuMetrics.unavailable("nvidia-smi", "command-unavailable");
        }

        Process process = null;
        try {
            process = new ProcessBuilder(
                    resolvedCommand.get(),
                    "--query-gpu=utilization.gpu,memory.used,memory.total",
                    "--format=csv,noheader,nounits"
            ).redirectErrorStream(true).start();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return ClientGpuMetrics.unavailable("nvidia-smi", "timeout");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                return ClientGpuMetrics.unavailable("nvidia-smi", "exit-" + process.exitValue());
            }
            return parse(output);
        } catch (IOException exception) {
            if ("nvidia-smi".equals(resolvedCommand.get())) {
                this.command = null;
                this.commandResolved = true;
            }
            return ClientGpuMetrics.unavailable("nvidia-smi", "io-" + exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ClientGpuMetrics.unavailable("nvidia-smi", "interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    static ClientGpuMetrics parse(String output) {
        if (output == null || output.isBlank()) {
            return ClientGpuMetrics.unavailable("nvidia-smi", "empty-output");
        }

        double maxUtilization = -1.0D;
        long totalUsed = 0L;
        long totalMemory = 0L;
        int parsedRows = 0;
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length < 3) {
                continue;
            }
            double utilization = parseDouble(parts[0]);
            long used = parseLong(parts[1]);
            long memory = parseLong(parts[2]);
            if (utilization < 0.0D || used < 0L || memory < 0L) {
                continue;
            }
            maxUtilization = Math.max(maxUtilization, utilization);
            totalUsed += used;
            totalMemory += memory;
            parsedRows += 1;
        }
        if (parsedRows <= 0) {
            return ClientGpuMetrics.unavailable("nvidia-smi", "unparsed-output");
        }
        return new ClientGpuMetrics(
                "nvidia-smi",
                true,
                maxUtilization,
                totalUsed,
                totalMemory,
                "gpus=" + parsedRows
        );
    }

    private Optional<String> resolveCommand() {
        if (this.commandResolved) {
            return Optional.ofNullable(this.command);
        }
        this.commandResolved = true;
        for (String candidate : this.commandCandidates()) {
            if ("nvidia-smi".equals(candidate) || Files.isRegularFile(Path.of(candidate))) {
                this.command = candidate;
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private List<String> commandCandidates() {
        List<String> candidates = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            candidates.add("C:\\Program Files\\NVIDIA Corporation\\NVSMI\\nvidia-smi.exe");
        }
        candidates.add("nvidia-smi");
        return candidates;
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(clean(value));
        } catch (NumberFormatException exception) {
            return -1.0D;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(clean(value));
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("%", "").replace("MiB", "").trim();
    }
}
