package io.github.luma.minecraft.world;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

final class WorldApplyMetrics {

    private int processedBlocks;
    private int changedBlocks;
    private int skippedBlocks;
    private int directSections;
    private int fallbackSections;
    private int rewriteSections;
    private int rewriteCells;
    private int rewriteFallbackSections;
    private int nativeSections;
    private int nativeCells;
    private int nativeFallbackSections;
    private int sectionPackets;
    private int blockEntityPackets;
    private int lightChecks;
    private int applyTicks;
    private int workTicks;
    private int maxWorkPerTick;
    private int lightDrainTicks;
    private long lightDrainNanos;
    private final Map<String, Integer> fallbackReasons = new LinkedHashMap<>();

    void record(BlockCommitResult result) {
        if (result == null) {
            return;
        }

        this.processedBlocks += result.processedBlocks();
        this.changedBlocks += result.changedBlocks();
        this.skippedBlocks += result.skippedBlocks();
        this.directSections += result.directSections();
        this.fallbackSections += result.fallbackSections();
        this.rewriteSections += result.rewriteSections();
        this.rewriteCells += result.rewriteCells();
        this.rewriteFallbackSections += result.rewriteFallbackSections();
        this.nativeSections += result.nativeSections();
        this.nativeCells += result.nativeCells();
        this.nativeFallbackSections += result.nativeFallbackSections();
        this.sectionPackets += result.sectionPackets();
        this.blockEntityPackets += result.blockEntityPackets();
        this.lightChecks += result.lightChecks();
        if ((result.fallbackSections() > 0 || result.rewriteFallbackSections() > 0 || result.nativeFallbackSections() > 0)
                && result.fallbackReason() != null
                && result.fallbackReason() != BlockCommitFallbackReason.NONE) {
            this.fallbackReasons.merge(
                    result.fallbackReason().label(),
                    result.fallbackSections() + result.rewriteFallbackSections() + result.nativeFallbackSections(),
                    Integer::sum
            );
        }
    }

    void recordLightChecks(int lightChecks) {
        this.lightChecks += Math.max(0, lightChecks);
    }

    void recordApplyTick(int workUnits) {
        this.applyTicks += 1;
        if (workUnits <= 0) {
            return;
        }
        this.workTicks += 1;
        this.maxWorkPerTick = Math.max(this.maxWorkPerTick, workUnits);
    }

    void recordLightDrainTick(long elapsedNanos) {
        this.lightDrainTicks += 1;
        this.lightDrainNanos += Math.max(0L, elapsedNanos);
    }

    int processedBlocks() {
        return this.processedBlocks;
    }

    int rewriteSections() {
        return this.rewriteSections;
    }

    int nativeSections() {
        return this.nativeSections;
    }

    int fallbackSections() {
        return this.fallbackSections + this.nativeFallbackSections + this.rewriteFallbackSections;
    }

    int lightChecks() {
        return this.lightChecks;
    }

    String summary() {
        return "processedBlocks=" + this.processedBlocks
                + ", changedBlocks=" + this.changedBlocks
                + ", skippedBlocks=" + this.skippedBlocks
                + ", directSections=" + this.directSections
                + ", fallbackSections=" + this.fallbackSections
                + ", rewriteSections=" + this.rewriteSections
                + ", rewriteCells=" + this.rewriteCells
                + ", rewriteFallbackSections=" + this.rewriteFallbackSections
                + ", nativeSections=" + this.nativeSections
                + ", nativeCells=" + this.nativeCells
                + ", nativeFallbackSections=" + this.nativeFallbackSections
                + ", sectionPackets=" + this.sectionPackets
                + ", blockEntityPackets=" + this.blockEntityPackets
                + ", lightChecks=" + this.lightChecks
                + ", applyTicks=" + this.applyTicks
                + ", workTicks=" + this.workTicks
                + ", avgWorkPerTick=" + this.avgWorkPerTick()
                + ", maxWorkPerTick=" + this.maxWorkPerTick
                + ", lightDrainTicks=" + this.lightDrainTicks
                + ", lightDrainDurationMs=" + this.lightDrainDurationMillis()
                + ", fallbackReasons=" + this.fallbackReasonsSummary();
    }

    private int avgWorkPerTick() {
        return this.workTicks <= 0 ? 0 : this.processedBlocks / this.workTicks;
    }

    private long lightDrainDurationMillis() {
        return this.lightDrainNanos / 1_000_000L;
    }

    private String fallbackReasonsSummary() {
        if (this.fallbackReasons.isEmpty()) {
            return "{}";
        }

        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        this.fallbackReasons.forEach((reason, count) -> joiner.add(reason + "=" + count));
        return joiner.toString();
    }
}
