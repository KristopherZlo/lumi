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

    boolean canStartNextStep(
            boolean hasPendingNativeSection,
            SectionApplyPath pendingNativePath,
            int processedWorkThisTick,
            int processedNativeSectionsThisTick,
            int processedNativeCellsThisTick,
            int processedRewriteSectionsThisTick,
            int processedDirectSectionsThisTick,
            WorldApplyBudget budget,
            WorldApplyProfile profile
    ) {
        return this.decide(
                hasPendingNativeSection,
                pendingNativePath,
                processedWorkThisTick,
                processedNativeSectionsThisTick,
                processedNativeCellsThisTick,
                processedRewriteSectionsThisTick,
                processedDirectSectionsThisTick,
                budget,
                profile
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
        return this.decide(
                hasPendingNativeSection,
                pendingNativePath,
                processedWorkThisTick,
                processedNativeSectionsThisTick,
                processedNativeCellsThisTick,
                processedRewriteSectionsThisTick,
                processedDirectSectionsThisTick,
                budget,
                WorldApplyProfile.NORMAL
        );
    }

    WorldApplyTickGateDecision decide(
            boolean hasPendingNativeSection,
            SectionApplyPath pendingNativePath,
            int processedWorkThisTick,
            int processedNativeSectionsThisTick,
            int processedNativeCellsThisTick,
            int processedRewriteSectionsThisTick,
            int processedDirectSectionsThisTick,
            WorldApplyBudget budget,
            WorldApplyProfile profile
    ) {
        if (budget == null) {
            return WorldApplyTickGateDecision.stop("no-budget");
        }
        boolean allowMixedApplyPaths = this.allowMixedApplyPaths(profile);
        if (hasPendingNativeSection) {
            return this.decideNativeStep(
                    pendingNativePath,
                    processedWorkThisTick,
                    processedNativeSectionsThisTick,
                    processedNativeCellsThisTick,
                    processedRewriteSectionsThisTick,
                    allowMixedApplyPaths,
                    budget
            );
        }
        if (!allowMixedApplyPaths && processedRewriteSectionsThisTick > 0) {
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
            boolean allowMixedApplyPaths,
            WorldApplyBudget budget
    ) {
        if (processedNativeSectionsThisTick >= budget.maxNativeSections()) {
            return WorldApplyTickGateDecision.stop("native-section-budget-consumed");
        }
        if (path == SectionApplyPath.SECTION_REWRITE) {
            boolean nonRewriteWorkAlreadyProcessed = processedWorkThisTick > 0
                    && processedRewriteSectionsThisTick == 0;
            if (!allowMixedApplyPaths && nonRewriteWorkAlreadyProcessed) {
                return WorldApplyTickGateDecision.stop("rewrite-after-non-rewrite-work");
            }
            if (processedRewriteSectionsThisTick >= budget.maxRewriteSections()) {
                return WorldApplyTickGateDecision.stop("rewrite-budget-consumed");
            }
            return WorldApplyTickGateDecision.allow();
        }
        if (!allowMixedApplyPaths && processedRewriteSectionsThisTick > 0) {
            return WorldApplyTickGateDecision.stop("native-after-rewrite-work");
        }
        if (processedNativeCellsThisTick >= budget.maxNativeCells()) {
            return WorldApplyTickGateDecision.stop("native-cell-budget-consumed");
        }
        return WorldApplyTickGateDecision.allow();
    }

    private boolean allowMixedApplyPaths(WorldApplyProfile profile) {
        return profile == WorldApplyProfile.DIAGNOSTIC_TURBO
                || profile == WorldApplyProfile.MAXIMUM;
    }
}
