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
    private int lightPrepared;
    private int lightDirtyChunks;
    private int lightEngineFlushTicks;
    private int redstoneUpdates;
    private int verificationMatched;
    private int verificationMismatched;
    private int verificationRepaired;
    private int verificationSkipped;
    private int applyFailures;
    private int applyTicks;
    private int workTicks;
    private int maxWorkPerTick;
    private int lightDrainTicks;
    private int redstoneDrainTicks;
    private long preparationNanos;
    private long preloadNanos;
    private long applyNanos;
    private long lightDrainNanos;
    private long redstoneDrainNanos;
    private long totalNanos;
    private long maxApplyTickNanos;
    private long maxPreloadTickNanos;
    private int preloadTicks;
    private int preloadedChunks;
    private int loadedBeforeApply;
    private int preloadTicketedChunks;
    private int preloadOutstandingTickets;
    private int preloadSyncFallbackLoads;
    private int missedAtApply;
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
            int fallbackCount = result.fallbackSections() + result.rewriteFallbackSections() + result.nativeFallbackSections();
            this.fallbackReasons.merge(
                    result.fallbackReason().label(),
                    fallbackCount,
                    Integer::sum
            );
            if (result.fallbackReason() == BlockCommitFallbackReason.CHUNK_NOT_LOADED) {
                this.missedAtApply += fallbackCount;
            }
        }
    }

    void recordLightChecks(int lightChecks) {
        this.lightChecks += Math.max(0, lightChecks);
    }

    void recordLightPrepared(int preparedChecks, int dirtyChunks) {
        this.lightPrepared += Math.max(0, preparedChecks);
        this.lightDirtyChunks += Math.max(0, dirtyChunks);
    }

    void recordLightEngineFlushTick() {
        this.lightEngineFlushTicks += 1;
    }

    void recordRedstoneUpdates(int redstoneUpdates) {
        this.redstoneUpdates += Math.max(0, redstoneUpdates);
    }

    void recordVerification(WorldApplyVerificationResult result) {
        if (result == null) {
            return;
        }
        this.verificationMatched += Math.max(0, result.matched());
        this.verificationMismatched += Math.max(0, result.mismatched());
        this.verificationRepaired += Math.max(0, result.repaired());
        this.verificationSkipped += Math.max(0, result.skipped());
    }

    void recordApplyFailure() {
        this.applyFailures += 1;
    }

    void recordApplyTick(int workUnits) {
        this.recordApplyTick(workUnits, 0L);
    }

    void recordApplyTick(int workUnits, long elapsedNanos) {
        this.applyTicks += 1;
        long sanitizedElapsedNanos = Math.max(0L, elapsedNanos);
        this.applyNanos += sanitizedElapsedNanos;
        this.maxApplyTickNanos = Math.max(this.maxApplyTickNanos, sanitizedElapsedNanos);
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

    void recordRedstoneDrainTick(long elapsedNanos) {
        this.redstoneDrainTicks += 1;
        this.redstoneDrainNanos += Math.max(0L, elapsedNanos);
    }

    void recordPreparationDuration(long elapsedNanos) {
        this.preparationNanos = Math.max(this.preparationNanos, Math.max(0L, elapsedNanos));
    }

    void recordPreloadTick(int newlyLoadedChunks, int alreadyLoadedChunks, long elapsedNanos) {
        this.preloadTicks += 1;
        long sanitizedElapsedNanos = Math.max(0L, elapsedNanos);
        this.preloadNanos += sanitizedElapsedNanos;
        this.maxPreloadTickNanos = Math.max(this.maxPreloadTickNanos, sanitizedElapsedNanos);
        this.preloadedChunks += Math.max(0, newlyLoadedChunks);
        this.loadedBeforeApply += Math.max(0, alreadyLoadedChunks);
    }

    void recordPreloadPipeline(int ticketedChunks, int outstandingTickets, int syncFallbackLoads) {
        this.preloadTicketedChunks += Math.max(0, ticketedChunks);
        this.preloadOutstandingTickets = Math.max(this.preloadOutstandingTickets, Math.max(0, outstandingTickets));
        this.preloadSyncFallbackLoads += Math.max(0, syncFallbackLoads);
    }

    void recordTotalDuration(long elapsedNanos) {
        this.totalNanos = Math.max(this.totalNanos, Math.max(0L, elapsedNanos));
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

    int lightEngineFlushTicks() {
        return this.lightEngineFlushTicks;
    }

    String summary() {
        return "processedBlocks=" + this.processedBlocks
                + ", prepareDurationMs=" + this.millis(this.preparationNanos)
                + ", preloadDurationMs=" + this.millis(this.preloadNanos)
                + ", preloadTicks=" + this.preloadTicks
                + ", preloadedChunks=" + this.preloadedChunks
                + ", loadedBeforeApply=" + this.loadedBeforeApply
                + ", preloadTicketedChunks=" + this.preloadTicketedChunks
                + ", preloadOutstandingTickets=" + this.preloadOutstandingTickets
                + ", preloadSyncFallbackLoads=" + this.preloadSyncFallbackLoads
                + ", missedAtApply=" + this.missedAtApply
                + ", applyDurationMs=" + this.millis(this.applyNanos)
                + ", redstoneFinalizeDurationMs=" + this.redstoneDrainDurationMillis()
                + ", lightFinalizeDurationMs=" + this.lightDrainDurationMillis()
                + ", totalOperationDurationMs=" + this.millis(this.totalNanos)
                + ", maxApplyTickMs=" + this.millis(this.maxApplyTickNanos)
                + ", maxPreloadTickMs=" + this.millis(this.maxPreloadTickNanos)
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
                + ", lightPrepared=" + this.lightPrepared
                + ", lightDirtyChunks=" + this.lightDirtyChunks
                + ", lightEngineFlushTicks=" + this.lightEngineFlushTicks
                + ", redstoneUpdates=" + this.redstoneUpdates
                + ", verificationMatched=" + this.verificationMatched
                + ", verificationMismatched=" + this.verificationMismatched
                + ", verificationRepaired=" + this.verificationRepaired
                + ", verificationSkipped=" + this.verificationSkipped
                + ", applyFailures=" + this.applyFailures
                + ", applyTicks=" + this.applyTicks
                + ", workTicks=" + this.workTicks
                + ", avgWorkPerTick=" + this.avgWorkPerTick()
                + ", maxWorkPerTick=" + this.maxWorkPerTick
                + ", lightDrainTicks=" + this.lightDrainTicks
                + ", redstoneDrainTicks=" + this.redstoneDrainTicks
                + ", redstoneDrainDurationMs=" + this.redstoneDrainDurationMillis()
                + ", lightDrainDurationMs=" + this.lightDrainDurationMillis()
                + ", fallbackReasons=" + this.fallbackReasonsSummary();
    }

    private int avgWorkPerTick() {
        return this.workTicks <= 0 ? 0 : this.processedBlocks / this.workTicks;
    }

    private long lightDrainDurationMillis() {
        return this.millis(this.lightDrainNanos);
    }

    private long redstoneDrainDurationMillis() {
        return this.millis(this.redstoneDrainNanos);
    }

    private long millis(long nanos) {
        return nanos / 1_000_000L;
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
