package io.github.luma.gametest;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.VersionSaveTiming;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Focused mutable state for the backup stress scenario's timing report.
 */
final class LumiBackupStressMetrics {

    private static final DateTimeFormatter LOG_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final int targetBlocks;
    private final int targetChunks;
    private final int backupBudgetMiB;

    Path saveDirectory;
    String projectName = "";
    long maxHeapMiB;
    long initialExitMs;
    long backupOpenMs;
    long manifestBackupMs;
    int scannedChunks;
    int backedUpChunks;
    long compressedBytes;
    long worldProjectCreateMs;
    long historySaveMs;
    private final Map<String, Long> historySaveTimingsMs = new LinkedHashMap<>();
    int historySavedBlocks;
    int historySavedChunks;
    int historyPatchCount;
    long modifiedExitMs;
    long offlineRestoreMs;
    int restoredChunks;
    long finalExitMs;

    LumiBackupStressMetrics(int targetBlocks, int targetChunks, int backupBudgetMiB) {
        this.targetBlocks = targetBlocks;
        this.targetChunks = targetChunks;
        this.backupBudgetMiB = backupBudgetMiB;
    }

    void recordVersionSaveTiming(VersionSaveTiming timing) {
        if (timing == null) {
            return;
        }
        this.historySaveTimingsMs.clear();
        for (String phase : VersionSaveTiming.PHASES) {
            this.historySaveTimingsMs.put(phase, timing.durationMs(phase));
        }
    }

    void write() {
        if (this.saveDirectory == null) {
            return;
        }
        try {
            Path log = this.saveDirectory.resolve("lumi")
                    .resolve("test-logs")
                    .resolve("backup-stress-" + LOG_STAMP.format(Instant.now()) + ".log");
            Files.createDirectories(log.getParent());
            Files.write(log, this.lines(), StandardCharsets.UTF_8);
            LumaMod.LOGGER.info("Lumi backup stress metrics written to {}", log);
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to write Lumi backup stress metrics", exception);
        }
    }

    private List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("Lumi backup stress metrics");
        lines.add("targetBlocks=" + this.targetBlocks);
        lines.add("targetChunks=" + this.targetChunks);
        lines.add("backupBudgetMiB=" + this.backupBudgetMiB);
        lines.add("projectName=" + this.projectName);
        lines.add("maxHeapMiB=" + this.maxHeapMiB);
        lines.add("initialExitMs=" + this.initialExitMs);
        lines.add("backupOpenMs=" + this.backupOpenMs);
        lines.add("manifestBackupMs=" + this.manifestBackupMs);
        lines.add("scannedChunks=" + this.scannedChunks);
        lines.add("backedUpChunks=" + this.backedUpChunks);
        lines.add("compressedBytes=" + this.compressedBytes);
        lines.add("worldProjectCreateMs=" + this.worldProjectCreateMs);
        lines.add("historySaveMs=" + this.historySaveMs);
        this.addHistorySaveTimingLines(lines);
        lines.add("historySavedBlocks=" + this.historySavedBlocks);
        lines.add("historySavedChunks=" + this.historySavedChunks);
        lines.add("historyPatchCount=" + this.historyPatchCount);
        lines.add("modifiedExitMs=" + this.modifiedExitMs);
        lines.add("offlineRestoreMs=" + this.offlineRestoreMs);
        lines.add("restoredChunks=" + this.restoredChunks);
        lines.add("finalExitMs=" + this.finalExitMs);
        return lines;
    }

    private void addHistorySaveTimingLines(List<String> lines) {
        for (String phase : VersionSaveTiming.PHASES) {
            long durationMs = this.historySaveTimingsMs.getOrDefault(phase, 0L);
            lines.add("historySave" + phase + "Ms=" + durationMs);
        }
    }
}
