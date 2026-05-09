package io.github.luma.client.world;

import io.github.luma.LumaMod;
import io.github.luma.minecraft.bootstrap.WorldInitialBackupWarningService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.network.chat.Component;

/**
 * Client-side gate shown before opening old worlds without Lumi's pre-mod backup.
 */
public final class WorldEntryWarningController {

    private static final WorldEntryWarningController INSTANCE = new WorldEntryWarningController();

    private final WorldInitialBackupWarningService warningService = new WorldInitialBackupWarningService();
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

        client.setScreen(new AlertScreen(
                () -> this.acknowledgeAndOpen(flows, levelId, onFailure, worldRoot),
                Component.translatable("luma.alpha_warning.title"),
                Component.translatable("luma.alpha_warning.message"),
                Component.translatable("gui.ok"),
                false
        ));
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

    private void acknowledgeAndOpen(
            WorldOpenFlows flows,
            String levelId,
            Runnable onFailure,
            Path worldRoot
    ) {
        try {
            this.warningService.acknowledgeWarning(worldRoot);
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to persist Lumi alpha backup warning acknowledgement for {}", levelId, exception);
        }
        this.bypassOnce.add(levelId);
        flows.openWorld(levelId, onFailure);
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
}
