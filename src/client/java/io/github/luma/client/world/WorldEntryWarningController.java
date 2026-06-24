package io.github.luma.client.world;

import io.github.luma.LumaMod;
import io.github.luma.minecraft.bootstrap.WorldInitialBackupIdentity;
import io.github.luma.minecraft.bootstrap.WorldInitialBackupIdentityReader;
import io.github.luma.minecraft.bootstrap.WorldInitialBackupService;
import io.github.luma.minecraft.bootstrap.WorldInitialBackupWarningService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.network.chat.Component;

/**
 * Client-side gate shown before opening old worlds without Lumi's pre-mod backup.
 */
public final class WorldEntryWarningController {

    private static final WorldEntryWarningController INSTANCE = new WorldEntryWarningController();

    private final WorldInitialBackupWarningService warningService = new WorldInitialBackupWarningService();
    private final WorldInitialBackupService backupService = new WorldInitialBackupService();
    private final WorldInitialBackupIdentityReader identityReader = new WorldInitialBackupIdentityReader();
    private final ExecutorService backupExecutor = Executors.newSingleThreadExecutor(WorldEntryWarningController::backupThread);
    private final Set<String> bypassOnce = ConcurrentHashMap.newKeySet();

    private WorldEntryWarningController() {
    }

    public static WorldEntryWarningController getInstance() {
        return INSTANCE;
    }

    public boolean showWarningIfNeeded(WorldOpenFlows flows, String levelId, Runnable onFailure) {
        if (flows == null || levelId == null || levelId.isBlank()) {
            return false;
        }
        if (this.bypassOnce.remove(levelId)) {
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        Path worldRoot = this.worldRoot(client, levelId);
        if (!this.shouldWarn(worldRoot, levelId)) {
            return false;
        }

        Runnable failureBack = onFailure == null ? () -> client.setScreen(null) : onFailure;
        WorldEntryBackupScreen screen = new WorldEntryBackupScreen(
                () -> this.backupAndOpen(client, flows, levelId, onFailure, worldRoot),
                failureBack
        );
        client.setScreen(screen);
        return true;
    }

    public void markCreatedWithLumi(String levelId) {
        if (levelId == null || levelId.isBlank()) {
            return;
        }
        Path worldRoot = this.worldRoot(Minecraft.getInstance(), levelId);
        try {
            this.warningService.markCreatedWithLumi(worldRoot);
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to mark newly created world {} as created with Lumi", levelId, exception);
        }
    }

    private void backupAndOpen(
            Minecraft client,
            WorldOpenFlows flows,
            String levelId,
            Runnable onFailure,
            Path worldRoot
    ) {
        WorldEntryBackupScreen screen = client.screen instanceof WorldEntryBackupScreen backupScreen
                ? backupScreen
                : null;
        CompletableFuture.runAsync(() -> this.createBackup(client, screen, levelId, worldRoot), this.backupExecutor)
                .whenComplete((ignored, throwable) -> client.execute(() -> {
                    if (throwable != null) {
                        LumaMod.LOGGER.warn("Failed to create pre-open Lumi backup for {}", levelId, throwable);
                        if (screen != null) {
                            screen.fail(Component.translatable("luma.alpha_warning.backup_failed"));
                        }
                        return;
                    }
                    if (screen != null) {
                        screen.markOpening();
                    }
                    this.bypassOnce.add(levelId);
                    flows.openWorld(levelId, onFailure);
                }));
    }

    private void createBackup(
            Minecraft client,
            WorldEntryBackupScreen screen,
            String levelId,
            Path worldRoot
    ) {
        try {
            WorldInitialBackupIdentity identity = this.identityReader.read(worldRoot, levelId);
            this.backupService.backupWorldRootIfNeeded(
                    worldRoot,
                    identity.levelName(),
                    identity.seed(),
                    progress -> client.execute(() -> {
                        if (screen != null) {
                            screen.updateProgress(progress);
                        }
                    })
            );
            this.warningService.acknowledgeWarning(worldRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("Pre-open Lumi backup failed", exception);
        }
    }

    private boolean shouldWarn(Path worldRoot, String levelId) {
        try {
            return this.warningService.shouldWarnBeforeOpen(worldRoot);
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to inspect Lumi backup warning state for {}", levelId, exception);
            return false;
        }
    }

    private Path worldRoot(Minecraft client, String levelId) {
        return client.getLevelSource().getLevelPath(levelId);
    }

    private static Thread backupThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "lumi-pre-open-backup");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    }
}
