package io.github.luma.minecraft.world;

import java.util.List;

record WorldApplyVerificationResult(
        int matched,
        int mismatched,
        int repaired,
        int skipped,
        List<SectionBatch> repairSections
) {

    WorldApplyVerificationResult {
        repairSections = repairSections == null ? List.of() : List.copyOf(repairSections);
    }

    static WorldApplyVerificationResult empty() {
        return new WorldApplyVerificationResult(0, 0, 0, 0, List.of());
    }

    WorldApplyVerificationResult withRepairOutcome(int repairedCount, int unrepairedCount) {
        return new WorldApplyVerificationResult(
                this.matched,
                this.mismatched,
                Math.max(0, repairedCount),
                this.skipped + Math.max(0, unrepairedCount),
                List.of()
        );
    }

    boolean hasRepairs() {
        return !this.repairSections.isEmpty();
    }

    String summary() {
        return "matched=" + this.matched
                + ", mismatched=" + this.mismatched
                + ", repaired=" + this.repaired
                + ", skipped=" + this.skipped;
    }
}
