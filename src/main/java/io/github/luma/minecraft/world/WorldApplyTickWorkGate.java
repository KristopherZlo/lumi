package io.github.luma.minecraft.world;

final class WorldApplyTickWorkGate {

    boolean canStartNextStep(
            boolean hasPendingNativeSection,
            SectionApplyPath pendingNativePath,
            int processedWorkThisTick,
            int processedNativeSectionsThisTick,
            int processedNativeCellsThisTick,
            int processedRewriteSectionsThisTick,
            WorldApplyBudget budget
    ) {
        if (budget == null) {
            return false;
        }
        if (hasPendingNativeSection) {
            return this.canStartNativeStep(
                    pendingNativePath,
                    processedWorkThisTick,
                    processedNativeSectionsThisTick,
                    processedNativeCellsThisTick,
                    processedRewriteSectionsThisTick,
                    budget
            );
        }
        return processedRewriteSectionsThisTick <= 0
                && processedWorkThisTick < budget.maxBlocks();
    }

    private boolean canStartNativeStep(
            SectionApplyPath path,
            int processedWorkThisTick,
            int processedNativeSectionsThisTick,
            int processedNativeCellsThisTick,
            int processedRewriteSectionsThisTick,
            WorldApplyBudget budget
    ) {
        if (processedNativeSectionsThisTick >= budget.maxNativeSections()) {
            return false;
        }
        if (path == SectionApplyPath.SECTION_REWRITE) {
            boolean nonRewriteWorkAlreadyProcessed = processedWorkThisTick > 0
                    && processedRewriteSectionsThisTick == 0;
            return processedRewriteSectionsThisTick < budget.maxRewriteSections()
                    && !nonRewriteWorkAlreadyProcessed;
        }
        return processedRewriteSectionsThisTick <= 0
                && processedNativeCellsThisTick < budget.maxNativeCells();
    }
}
