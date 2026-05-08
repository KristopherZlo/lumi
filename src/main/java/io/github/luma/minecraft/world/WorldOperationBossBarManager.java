package io.github.luma.minecraft.world;

import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * Presents active Lumi world operations through Minecraft's native bossbar UI.
 */
public final class WorldOperationBossBarManager {

    private ServerBossEvent bossBar;

    public void tick(MinecraftServer server) {
        if (server == null) {
            this.clear();
            return;
        }

        OperationSnapshot snapshot = WorldOperationManager.getInstance().snapshot(server).orElse(null);
        if (snapshot == null || snapshot.terminal()) {
            this.clear();
            return;
        }

        ServerBossEvent event = this.ensureBossBar();
        event.setName(this.title(snapshot));
        event.setProgress(this.progress(snapshot));
        event.setColor(this.color(snapshot.stage()));
        event.setOverlay(BossEvent.BossBarOverlay.PROGRESS);
        this.syncPlayers(event, server.getPlayerList().getPlayers());
    }

    public void clear() {
        if (this.bossBar != null) {
            this.bossBar.removeAllPlayers();
            this.bossBar = null;
        }
    }

    private ServerBossEvent ensureBossBar() {
        if (this.bossBar == null) {
            this.bossBar = new ServerBossEvent(
                    Component.literal("Lumi"),
                    BossEvent.BossBarColor.BLUE,
                    BossEvent.BossBarOverlay.PROGRESS
            );
        }
        return this.bossBar;
    }

    private void syncPlayers(ServerBossEvent event, Collection<ServerPlayer> players) {
        Set<ServerPlayer> activePlayers = new HashSet<>(players);
        for (ServerPlayer player : players) {
            event.addPlayer(player);
        }
        for (ServerPlayer player : List.copyOf(event.getPlayers())) {
            if (!activePlayers.contains(player)) {
                event.removePlayer(player);
            }
        }
    }

    private Component title(OperationSnapshot snapshot) {
        String label = snapshot.handle() == null ? "operation" : snapshot.handle().label();
        OperationProgress progress = snapshot.progress();
        String stage = snapshot.stage().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String progressText = progress.totalUnits() <= 0
                ? stage
                : progress.completedUnits() + "/" + progress.totalUnits() + " " + progress.unitLabel();
        String detail = snapshot.detail() == null || snapshot.detail().isBlank()
                ? ""
                : " - " + snapshot.detail();
        return Component.literal("Lumi: " + label + " - " + progressText + detail);
    }

    private float progress(OperationSnapshot snapshot) {
        OperationProgress progress = snapshot.progress();
        if (progress.totalUnits() > 0) {
            return (float) progress.fraction();
        }
        return switch (snapshot.stage()) {
            case QUEUED -> 0.02F;
            case PREPARING -> 0.08F;
            case PRELOADING -> 0.2F;
            case WRITING -> 0.45F;
            case APPLYING -> 0.55F;
            case FINALIZING -> 0.95F;
            case COMPLETED -> 1.0F;
            case FAILED -> 1.0F;
        };
    }

    private BossEvent.BossBarColor color(OperationStage stage) {
        return switch (stage) {
            case QUEUED, PREPARING -> BossEvent.BossBarColor.WHITE;
            case PRELOADING -> BossEvent.BossBarColor.BLUE;
            case WRITING, FINALIZING -> BossEvent.BossBarColor.YELLOW;
            case APPLYING -> BossEvent.BossBarColor.GREEN;
            case COMPLETED -> BossEvent.BossBarColor.GREEN;
            case FAILED -> BossEvent.BossBarColor.RED;
        };
    }

}
