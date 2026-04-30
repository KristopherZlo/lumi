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
                + ", fallbackReasons=" + this.fallbackReasonsSummary();
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
