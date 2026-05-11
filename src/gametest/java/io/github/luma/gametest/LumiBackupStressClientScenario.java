package io.github.luma.gametest;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorldInitialBackupManifest;
import io.github.luma.minecraft.bootstrap.WorldInitialBackupRestoreService;
import io.github.luma.storage.repository.WorldInitialBackupRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.impl.client.gametest.context.TestSingleplayerContextImpl;
import net.minecraft.server.MinecraftServer;

/**
 * Client GameTest scenario that exercises a real pre-open backup gate, Lumi
 * history save, large world exits, offline restore, and restored-state checks.
 */
@SuppressWarnings("UnstableApiUsage")
final class LumiBackupStressClientScenario {

    private static final String ACTOR = "Lumi backup stress";
    private static final String MAX_MIB_PROPERTY = "lumi.preModBackup.maxMiB";
    private static final String BACKUP_SCREEN_CLASS = "io.github.luma.client.world.WorldEntryBackupScreen";
    private static final String ACCEPT_BUTTON_KEY = "luma.alpha_warning.accept";
    private static final int BACKUP_BUDGET_MIB = 1024;
    private static final int BACKUP_SCREEN_TIMEOUT_TICKS = 20 * 30;
    private static final int WORLD_LOAD_TIMEOUT_TICKS = 20 * 240;
    private static final long MAX_BACKUP_OPEN_MS = 120_000L;
    private static final long MAX_OFFLINE_RESTORE_MS = 120_000L;
    private static final long MAX_HISTORY_SAVE_MS = 120_000L;
    private static final long MAX_EXIT_MS = 60_000L;

    private final LumiBackupStressWorkload workload = new LumiBackupStressWorkload(ACTOR);
    private final LumiBackupStressHistorySaveFlow historySaveFlow = new LumiBackupStressHistorySaveFlow(
            ACTOR,
            this.workload.targetBlocks(),
            this.workload.targetChunks()
    );
    private final WorldInitialBackupRepository backupRepository = new WorldInitialBackupRepository();
    private final WorldInitialBackupRestoreService restoreService = new WorldInitialBackupRestoreService();

    void run(ClientGameTestContext context) throws Exception {
        LumiBackupStressMetrics metrics = new LumiBackupStressMetrics(
                this.workload.targetBlocks(),
                this.workload.targetChunks(),
                BACKUP_BUDGET_MIB
        );
        String previousBudget = System.getProperty(MAX_MIB_PROPERTY);
        try {
            TestWorldSave save = this.createAndClosePreLumiBaseline(context, metrics);
            this.prepareExistingPreLumiSave(save.getSaveDirectory());

            System.setProperty(MAX_MIB_PROPERTY, Integer.toString(BACKUP_BUDGET_MIB));
            TestSingleplayerContext backedUp = this.openThroughBackupGate(context, save, metrics);
            try {
                this.inspectBackupManifest(save.getSaveDirectory(), metrics);
                BuildProject project = this.historySaveFlow.createWorldWorkspace(backedUp, metrics);
                this.workload.placeAll(
                        context,
                        backedUp,
                        LumiBackupStressWorkload.StressState.MODIFIED,
                        "second 100k modification",
                        true
                );
                this.workload.verifyAll(
                        context,
                        backedUp,
                        LumiBackupStressWorkload.StressState.MODIFIED,
                        "second modification before exit"
                );
                this.historySaveFlow.commitHistorySave(context, backedUp, project, metrics);
            } finally {
                metrics.modifiedExitMs = this.closeMeasured(backedUp, "after second 100k modification");
            }

            this.restoreOffline(save.getSaveDirectory(), metrics);
            TestSingleplayerContext restored = save.open();
            try {
                ClientGameTestSingleplayerSupport.prepare(restored);
                this.workload.verifyAll(
                        context,
                        restored,
                        LumiBackupStressWorkload.StressState.BASELINE,
                        "pre-Lumi backup restore"
                );
            } finally {
                metrics.finalExitMs = this.closeMeasured(restored, "after backup restore verification");
            }

            this.assertTimings(metrics);
        } finally {
            this.restoreBudgetProperty(previousBudget);
            metrics.write();
        }
    }

    private TestWorldSave createAndClosePreLumiBaseline(
            ClientGameTestContext context,
            LumiBackupStressMetrics metrics
    ) throws Exception {
        TestSingleplayerContext singleplayer = context.worldBuilder().create();
        try {
            ClientGameTestSingleplayerSupport.prepare(singleplayer);
            TestWorldSave save = singleplayer.getWorldSave();
            metrics.saveDirectory = save.getSaveDirectory();
            metrics.maxHeapMiB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
            this.workload.placeAll(
                    context,
                    singleplayer,
                    LumiBackupStressWorkload.StressState.BASELINE,
                    "initial 100k pre-Lumi baseline",
                    false
            );
            metrics.initialExitMs = this.closeMeasured(singleplayer, "after initial 100k baseline");
            return save;
        } catch (Exception | Error exception) {
            this.closeQuietly(singleplayer, metrics, "after failed initial baseline");
            throw exception;
        }
    }

    private TestSingleplayerContext openThroughBackupGate(
            ClientGameTestContext context,
            TestWorldSave save,
            LumiBackupStressMetrics metrics
    ) throws Exception {
        String levelId = save.getSaveDirectory().getFileName().toString();
        context.runOnClient(client -> client.createWorldOpenFlows().openWorld(levelId, () -> {
        }));
        context.waitFor(
                client -> client.screen != null && BACKUP_SCREEN_CLASS.equals(client.screen.getClass().getName()),
                BACKUP_SCREEN_TIMEOUT_TICKS
        );

        long startedAt = System.nanoTime();
        context.clickScreenButton(ACCEPT_BUTTON_KEY);
        context.waitFor(client -> client.level != null && client.getSingleplayerServer() != null, WORLD_LOAD_TIMEOUT_TICKS);
        metrics.backupOpenMs = elapsedMillis(startedAt);
        MinecraftServer server = context.computeOnClient(client -> client.getSingleplayerServer());
        return new TestSingleplayerContextImpl(context, save, server);
    }

    private void prepareExistingPreLumiSave(Path saveDirectory) throws IOException {
        Path lumiRoot = saveDirectory.resolve("lumi");
        Files.deleteIfExists(lumiRoot.resolve("created-with-lumi.marker"));
        Files.deleteIfExists(lumiRoot.resolve("world-origin.json"));
        this.deleteRecursively(saveDirectory, lumiRoot.resolve("pre-mod-backup"));
        if (Files.exists(lumiRoot.resolve("created-with-lumi.marker"))) {
            throw new IOException("Fresh test save still has a Lumi creation marker");
        }
    }

    private void inspectBackupManifest(Path saveDirectory, LumiBackupStressMetrics metrics) throws IOException {
        WorldInitialBackupManifest manifest = this.backupRepository.load(saveDirectory)
                .orElseThrow(() -> new IOException("Pre-open backup manifest was not created"));
        WorldInitialBackupManifest.DimensionBackupSummary overworld = manifest.dimensions().get("minecraft:overworld");
        if (overworld == null) {
            throw new IOException("Pre-open backup manifest has no overworld summary");
        }
        metrics.manifestBackupMs = Duration.between(manifest.startedAt(), manifest.completedAt()).toMillis();
        metrics.scannedChunks = overworld.scannedChunks();
        metrics.backedUpChunks = overworld.backedUpChunks();
        metrics.compressedBytes = overworld.compressedBytes();
        if (manifest.maxCompressedBytes() != BACKUP_BUDGET_MIB * 1024L * 1024L) {
            throw new IOException("Backup manifest used unexpected budget: " + manifest.maxCompressedBytes());
        }
        if (overworld.backedUpChunks() < this.workload.targetChunks()) {
            throw new IOException("Backup captured too few chunks: " + overworld.backedUpChunks()
                    + "/" + this.workload.targetChunks());
        }
    }

    private void restoreOffline(Path saveDirectory, LumiBackupStressMetrics metrics) throws IOException {
        if (!this.restoreService.hasRestorableBackup(saveDirectory)) {
            throw new IOException("Pre-open backup is not restorable");
        }
        long startedAt = System.nanoTime();
        WorldInitialBackupRestoreService.RestoreResult result = this.restoreService.restore(saveDirectory);
        metrics.offlineRestoreMs = elapsedMillis(startedAt);
        metrics.restoredChunks = result.restoredChunks();
        if (result.restoredChunks() < this.workload.targetChunks()) {
            throw new IOException("Restored too few backup chunks: " + result.restoredChunks()
                    + "/" + this.workload.targetChunks());
        }
    }

    private void assertTimings(LumiBackupStressMetrics metrics) {
        this.assertAtMost("pre-open backup open wall time", metrics.backupOpenMs, MAX_BACKUP_OPEN_MS);
        this.assertAtMost("offline pre-mod backup restore", metrics.offlineRestoreMs, MAX_OFFLINE_RESTORE_MS);
        this.assertAtMost("Lumi history save after second 100k modification", metrics.historySaveMs, MAX_HISTORY_SAVE_MS);
        this.assertAtMost("exit after initial 100k baseline", metrics.initialExitMs, MAX_EXIT_MS);
        this.assertAtMost("exit after second 100k modification", metrics.modifiedExitMs, MAX_EXIT_MS);
        this.assertAtMost("exit after restore verification", metrics.finalExitMs, MAX_EXIT_MS);
    }

    private void assertAtMost(String label, long actualMs, long maxMs) {
        if (actualMs > maxMs) {
            throw new AssertionError(label + " took " + actualMs + " ms, max " + maxMs + " ms");
        }
    }

    private long closeMeasured(TestSingleplayerContext singleplayer, String label) {
        long startedAt = System.nanoTime();
        singleplayer.close();
        long durationMs = elapsedMillis(startedAt);
        LumaMod.LOGGER.info("Lumi backup stress world exit complete: label={} durationMs={}", label, durationMs);
        return durationMs;
    }

    private void closeQuietly(TestSingleplayerContext singleplayer, LumiBackupStressMetrics metrics, String label) {
        try {
            if (singleplayer != null) {
                metrics.finalExitMs = this.closeMeasured(singleplayer, label);
            }
        } catch (RuntimeException exception) {
            LumaMod.LOGGER.warn("Failed to close backup stress world after {}", label, exception);
        }
    }

    private void restoreBudgetProperty(String previousBudget) {
        if (previousBudget == null) {
            System.clearProperty(MAX_MIB_PROPERTY);
        } else {
            System.setProperty(MAX_MIB_PROPERTY, previousBudget);
        }
    }

    private void deleteRecursively(Path saveDirectory, Path target) throws IOException {
        Path normalizedRoot = saveDirectory.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new IOException("Refusing to delete outside test save: " + target);
        }
        if (!Files.exists(target)) {
            return;
        }
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
