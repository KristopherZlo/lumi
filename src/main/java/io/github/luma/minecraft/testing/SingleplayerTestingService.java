package io.github.luma.minecraft.testing;

import io.github.luma.minecraft.world.WorldOperationManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Owns the single active in-world test suite run for an integrated server.
 */
public final class SingleplayerTestingService {

    private static final SingleplayerTestingService INSTANCE = new SingleplayerTestingService();

    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();
    private SingleplayerTestRun activeRun;

    private SingleplayerTestingService() {
    }

    public static SingleplayerTestingService getInstance() {
        return INSTANCE;
    }

    public synchronized int start(CommandSourceStack source) throws Exception {
        MinecraftServer server = source.getServer();
        if (server.isDedicatedServer()) {
            throw new IllegalStateException("/lumi testing singleplayer can only run in an integrated singleplayer world");
        }
        if (this.activeRun != null) {
            throw new IllegalStateException("A Lumi singleplayer test run is already active");
        }
        if (this.worldOperationManager.hasActiveOperation(server)) {
            throw new IllegalStateException("Wait for the active Lumi world operation to finish before testing");
        }

        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        SingleplayerTestVolume volume = SingleplayerTestVolume.find(level, player.blockPosition())
                .orElseThrow(() -> new IllegalStateException("No empty 5x4x5 air volume was found above the player chunk"));
        this.activeRun = new SingleplayerTestRun(server, level, player, volume);
        this.activeRun.message(server, "Lumi singleplayer testing started at " + this.activeRun.describeVolume());
        return 1;
    }

    public synchronized void tick(MinecraftServer server) {
        if (this.activeRun == null || !this.activeRun.matches(server)) {
            return;
        }

        try {
            this.activeRun.tick(server);
            if (this.activeRun.done()) {
                this.activeRun = null;
            }
        } catch (Exception exception) {
            this.activeRun.fail(server, exception);
            this.activeRun = null;
        }
    }

    static void send(ServerPlayer player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
