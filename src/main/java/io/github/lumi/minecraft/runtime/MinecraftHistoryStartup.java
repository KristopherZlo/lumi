package io.github.lumi.minecraft.runtime;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.domain.service.WorldHistoryUpgradeService;
import io.github.lumi.minecraft.operation.OperationProgress;
import io.github.lumi.minecraft.world.MinecraftWorldPayloadConverter;
import io.github.lumi.mixin.MinecraftServerAccessor;
import io.github.lumi.storage.repository.DimensionRepositoryLayout;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Owns startup conversion after vanilla file migration and before any dimension can simulate. */
final class MinecraftHistoryStartup {
    private MinecraftHistoryStartup() { }

    static void run(MinecraftServer server) throws IOException {
        var current = new WorldDataVersion(SharedConstants.getCurrentVersion().dataVersion().version());
        // Enable another source/target pair only after its stopped-world native upgrade fixture passes.
        Set<WorldDataVersion> permittedSources = Set.of(4786, 4903, 5023).contains(current.value())
                ? Set.of(WorldDataVersion.LEGACY_12111) : Set.of();
        var upgrade = new WorldHistoryUpgradeService(
                new DimensionRepositoryLayout(server.getWorldPath(LevelResource.ROOT)), current,
                new MinecraftWorldPayloadConverter(server.registryAccess(), BuiltInRegistries.ENTITY_TYPE),
                permittedSources);
        var progress = new AtomicReference<>(OperationProgress.indeterminate("Checking history compatibility"));
        try (var worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "Lumi-History-Upgrade"))) {
            var result = worker.submit(() -> upgrade.upgrade(() -> {
                progress.set(OperationProgress.indeterminate("Backing up world before history upgrade"));
                long bytes = ((MinecraftServerAccessor) server).lumi$storageSource().makeWorldBackup();
                LumiMod.LOGGER.info("Lumi pre-upgrade world backup completed: {} bytes", bytes);
            }, state -> {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("History upgrade interrupted");
                }
                progress.set(OperationProgress.indeterminate(
                        "Converting history: " + state.phase() + " (" + state.completed() + ")"));
            }));
            try {
                for (;;) {
                    try {
                        int upgraded = result.get(1, TimeUnit.SECONDS);
                        if (upgraded > 0) LumiMod.LOGGER.info("Lumi history upgrade completed for {} dimensions", upgraded);
                        return;
                    } catch (TimeoutException pending) {
                        LumiMod.LOGGER.info("Lumi startup: {}", progress.get().phase());
                    }
                }
            } catch (InterruptedException interrupted) {
                result.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("History upgrade was interrupted; world startup was cancelled", interrupted);
            } catch (ExecutionException failed) {
                throw new IOException("History upgrade failed; world startup was cancelled", failed.getCause());
            }
        }
    }
}
