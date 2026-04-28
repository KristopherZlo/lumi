package io.github.luma.minecraft.testing;

import io.github.luma.minecraft.world.WorldOperationManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Owns the single active in-world test suite run for an integrated server.
 */
public final class SingleplayerTestingService {

    private static final SingleplayerTestingService INSTANCE = new SingleplayerTestingService();

    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();
    private SingleplayerTestRun activeRun;
    private String lastRunServerKey = "";
    private boolean lastRunPassed;

    private SingleplayerTestingService() {
    }

    public static SingleplayerTestingService getInstance() {
        return INSTANCE;
    }

    public synchronized int start(CommandSourceStack source) throws Exception {
        return this.start(source.getServer(), source.getLevel(), source.getPlayerOrException());
    }

    public synchronized int start(MinecraftServer server, ServerLevel level, ServerPlayer player) throws Exception {
        if (server.isDedicatedServer()) {
            throw new IllegalStateException("/lumi testing singleplayer can only run in an integrated singleplayer world");
        }
        if (this.activeRun != null) {
            throw new IllegalStateException("A Lumi singleplayer test run is already active");
        }
        if (this.worldOperationManager.hasActiveOperation(server)) {
            throw new IllegalStateException("Wait for the active Lumi world operation to finish before testing");
        }

        SingleplayerTestVolume volume = SingleplayerTestVolume.find(level, player.blockPosition())
                .orElseThrow(() -> new IllegalStateException("No empty 5x4x5 air volume was found above the player chunk"));
        this.lastRunServerKey = serverKey(server);
        this.lastRunPassed = false;
        this.activeRun = new SingleplayerTestRun(server, level, player, volume);
        this.activeRun.message(server, "Lumi singleplayer testing started at " + this.activeRun.describeVolume());
        return 1;
    }

    public synchronized boolean hasActiveRun(MinecraftServer server) {
        return this.activeRun != null && this.activeRun.matches(server);
    }

    public synchronized boolean lastRunPassed(MinecraftServer server) {
        return this.lastRunServerKey.equals(serverKey(server)) && this.lastRunPassed;
    }

    public synchronized void tick(MinecraftServer server) {
        if (this.activeRun == null || !this.activeRun.matches(server)) {
            return;
        }

        try {
            this.activeRun.tick(server);
            if (this.activeRun.done()) {
                this.rememberLastRun(server, this.activeRun.passed());
                this.activeRun = null;
            }
        } catch (Exception exception) {
            this.activeRun.fail(server, exception);
            this.rememberLastRun(server, false);
            this.activeRun = null;
        }
    }

    private void rememberLastRun(MinecraftServer server, boolean passed) {
        this.lastRunServerKey = serverKey(server);
        this.lastRunPassed = passed;
    }

    private static String serverKey(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
    }

    static void send(ServerPlayer player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
