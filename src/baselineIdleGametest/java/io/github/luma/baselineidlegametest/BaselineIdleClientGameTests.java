package io.github.luma.baselineidlegametest;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

@SuppressWarnings("UnstableApiUsage")
public final class BaselineIdleClientGameTests implements FabricClientGameTest {

    private static final int IDLE_TICKS = 20;
    private static final long WORLD_SEED = 6840143426479848331L;
    private static final List<BlockPos> TELEPORT_TARGETS = List.of(
            new BlockPos(2048, 300, 0),
            new BlockPos(0, 300, 2048),
            new BlockPos(-2048, 300, -2048)
    );

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> settings.setSeed(Long.toString(WORLD_SEED)))
                .create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            context.waitTicks(IDLE_TICKS);
            int completedTeleports = this.runTeleportLoads(context, singleplayer);
            long actualSeed = singleplayer.getServer().computeOnServer(server -> server.overworld().getSeed());
            this.report(actualSeed, completedTeleports);
            context.takeScreenshot("lumi-baseline-idle-client-smoke");
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Lumi baseline idle client gametest failed", exception);
        }
    }

    private int runTeleportLoads(ClientGameTestContext context, TestSingleplayerContext singleplayer) throws Exception {
        int completed = 0;
        for (BlockPos target : TELEPORT_TARGETS) {
            long startedAt = System.nanoTime();
            boolean arrived = singleplayer.getServer().computeOnServer(server -> {
                List<ServerPlayer> players = server.getPlayerList().getPlayers();
                if (players.isEmpty()) {
                    throw new IllegalStateException("No baseline idle test player is available");
                }
                ServerPlayer player = players.getFirst();
                player.setNoGravity(true);
                player.teleportTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
                return player.blockPosition().getX() == target.getX()
                        && player.blockPosition().getZ() == target.getZ();
            });
            if (!arrived) {
                break;
            }
            context.waitTick();
            int renderWaitTicks = singleplayer.getClientWorld().waitForChunksRender();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            context.waitTicks(IDLE_TICKS);
            completed++;
            System.out.println("Lumi baseline idle teleport load: index=" + completed
                    + ", seed=" + WORLD_SEED
                    + ", elapsedMs=" + elapsedMs
                    + ", renderWaitTicks=" + renderWaitTicks);
        }
        return completed;
    }

    private void report(long actualSeed, int completedTeleports) {
        boolean lumiAbsent = !FabricLoader.getInstance().isModLoaded("lumi");
        boolean seedMatches = actualSeed == WORLD_SEED;
        boolean teleportsCompleted = completedTeleports == TELEPORT_TARGETS.size();
        String result = lumiAbsent && seedMatches && teleportsCompleted ? "passed" : "completed with failures";
        int passed = (lumiAbsent ? 1 : 0) + (seedMatches ? 1 : 0) + (teleportsCompleted ? 1 : 0);
        int failed = 3 - passed;
        System.out.println("Lumi baseline idle startup seed: expected=" + WORLD_SEED + ", actual=" + actualSeed);
        System.out.println("Lumi baseline idle startup testing " + result + ": "
                + passed + " passed, " + failed + " failed");
        if (!lumiAbsent) {
            throw new AssertionError("Lumi mod was loaded in baseline idle startup test");
        }
        if (!seedMatches) {
            throw new AssertionError("Baseline idle startup world used a different seed: " + actualSeed);
        }
        if (!teleportsCompleted) {
            throw new AssertionError("Baseline idle startup completed " + completedTeleports
                    + "/" + TELEPORT_TARGETS.size() + " teleports");
        }
    }
}
