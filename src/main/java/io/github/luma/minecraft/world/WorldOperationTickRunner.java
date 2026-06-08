package io.github.luma.minecraft.world;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.telemetry.TelemetryService;
import java.util.function.BiConsumer;
import net.minecraft.server.MinecraftServer;

/**
 * Advances one active world operation for one server tick.
 */
final class WorldOperationTickRunner {

    private final WorldApplyBudgetPlanner budgetPlanner;
    private final WorldApplyOperationProfile applyOperationProfile;

    WorldOperationTickRunner(
            WorldApplyBudgetPlanner budgetPlanner,
            WorldApplyOperationProfile applyOperationProfile
    ) {
        this.budgetPlanner = budgetPlanner;
        this.applyOperationProfile = applyOperationProfile;
    }

    void advance(
            MinecraftServer server,
            WorldOperationManager.ActiveOperation operation,
            BiConsumer<MinecraftServer, WorldOperationManager.ActiveOperation> completionHandler
    ) {
        if (operation == null) {
            return;
        }
        try {
            WorldApplyBudget budget = this.currentTickBudget(operation);
            long startedAt = System.nanoTime();
            if (operation.advance(budget, startedAt + budget.maxNanos())) {
                completionHandler.accept(server, operation);
            }
            long elapsedNanos = System.nanoTime() - startedAt;
            operation.recordAdvanceCost(elapsedNanos, budget.maxNanos());
            if (elapsedNanos > Math.max(50_000_000L, budget.maxNanos() * 2L)) {
                TelemetryService.getInstance().recordPerformanceOutlier(
                        operation.handle().label(),
                        elapsedNanos,
                        budget.maxNanos(),
                        operation.snapshot().stage().name()
                );
            }
            LumaLoadLog.record(
                    "world-op-tick",
                    operation.handle().label() + ".advance",
                    elapsedNanos,
                    "stage=" + operation.snapshot().stage()
                            + ", budgetMicros=" + (budget.maxNanos() / 1_000L)
                            + ", adaptiveScale=" + operation.adaptiveScale()
            );
        } catch (Exception exception) {
            operation.fail(exception);
            TelemetryService.getInstance().recordOperationFailed(operation.handle(), operation.snapshot(), exception);
            completionHandler.accept(server, operation);
            LumaMod.LOGGER.warn("World operation {} failed", operation.handle().label(), exception);
        }
    }

    private WorldApplyBudget currentTickBudget(WorldOperationManager.ActiveOperation operation) {
        double fraction = operation.snapshot().progress().fraction();
        WorldApplyProfile profile = this.applyProfile(operation);
        return operation.planBudget(this.budgetPlanner, fraction, profile);
    }

    private WorldApplyProfile applyProfile(WorldOperationManager.ActiveOperation operation) {
        if (operation == null || operation.handle() == null) {
            return WorldApplyProfile.NORMAL;
        }
        return this.applyOperationProfile.profileFor(operation.handle().label());
    }
}
