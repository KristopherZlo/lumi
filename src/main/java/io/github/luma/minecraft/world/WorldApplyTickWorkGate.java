package io.github.luma.minecraft.world;

final class WorldApplyTickWorkGate {

    boolean canStartNextStep(
            boolean hasPendingNativeSection,
            SectionApplyPath pendingNativePath,
            int processedWorkThisTick,
            int processedNativeSectionsThisTick,
            int processedNativeCellsThisTick,
            int processedRewriteSectionsThisTick,
            int processedDirectSectionsThisTick,
            WorldApplyBudget budget
    ) {
        return this.decide(
                hasPendingNativeSection,
                pendingNativePath,
                processedWorkThisTick,
                processedNativeSectionsThisTick,
                processedNativeCellsThisTick,
                processedRewriteSectionsThisTick,
                processedDirectSectionsThisTick,
                budget
        ).canStart();
    }

    WorldApplyTickGateDecision decide(
            boolean hasPendingNativeSection,
            SectionApplyPath pendingNativePath,
            int processedWorkThisTick,
            int processedNativeSectionsThisTick,
            int processedNativeCellsThisTick,
            int processedRewriteSectionsThisTick,
            int processedDirectSectionsThisTick,
            WorldApplyBudget budget
    ) {
        if (budget == null) {
            return WorldApplyTickGateDecision.stop("no-budget");
        }
        if (hasPendingNativeSection) {
            return this.decideNativeStep(
                    pendingNativePath,
                    processedWorkThisTick,
                    processedNativeSectionsThisTick,
                    processedNativeCellsThisTick,
                    processedRewriteSectionsThisTick,
                    budget
            );
        }
        if (processedRewriteSectionsThisTick > 0) {
            return WorldApplyTickGateDecision.stop("sparse-after-rewrite-work");
        }
        if (processedWorkThisTick >= budget.maxBlocks()) {
            return WorldApplyTickGateDecision.stop("block-budget-consumed");
        }
        if (processedDirectSectionsThisTick >= budget.maxDirectSections()) {
            return WorldApplyTickGateDecision.stop("direct-section-budget-consumed");
        }
        return WorldApplyTickGateDecision.allow();
    }

    private WorldApplyTickGateDecision decideNativeStep(
            SectionApplyPath path,
            int processedWorkThisTick,
            int processedNativeSectionsThisTick,
            int processedNativeCellsThisTick,
            int processedRewriteSectionsThisTick,
            WorldApplyBudget budget
    ) {
        if (processedNativeSectionsThisTick >= budget.maxNativeSections()) {
            return WorldApplyTickGateDecision.stop("native-section-budget-consumed");
        }
        if (path == SectionApplyPath.SECTION_REWRITE) {
            boolean nonRewriteWorkAlreadyProcessed = processedWorkThisTick > 0
                    && processedRewriteSectionsThisTick == 0;
            if (nonRewriteWorkAlreadyProcessed) {
                return WorldApplyTickGateDecision.stop("rewrite-after-non-rewrite-work");
            }
            if (processedRewriteSectionsThisTick >= budget.maxRewriteSections()) {
                return WorldApplyTickGateDecision.stop("rewrite-budget-consumed");
            }
            return WorldApplyTickGateDecision.allow();
        }
        if (processedRewriteSectionsThisTick > 0) {
            return WorldApplyTickGateDecision.stop("native-after-rewrite-work");
        }
        if (processedNativeCellsThisTick >= budget.maxNativeCells()) {
            return WorldApplyTickGateDecision.stop("native-cell-budget-consumed");
        }
        return WorldApplyTickGateDecision.allow();
    }
}
