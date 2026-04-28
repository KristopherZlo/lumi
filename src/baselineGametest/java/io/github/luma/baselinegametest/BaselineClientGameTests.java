package io.github.luma.baselinegametest;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

@SuppressWarnings("UnstableApiUsage")
public final class BaselineClientGameTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        BaselineChecks checks = new BaselineChecks();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            singleplayer.getServer().runOnServer(server -> this.runGameplayScenario(server, checks));
            context.waitTicks(5);
            this.report(checks);
            context.takeScreenshot("lumi-baseline-client-gameplay");
        } catch (RuntimeException | Error exception) {
            this.report(checks);
            throw exception;
        } catch (Exception exception) {
            this.report(checks);
            throw new RuntimeException("Lumi baseline gameplay testing failed", exception);
        }
    }

    private void runGameplayScenario(MinecraftServer server, BaselineChecks checks) {
        ServerLevel level = server.overworld();
        ServerPlayer player = this.firstPlayer(server);
        BlockPos origin = player.blockPosition().offset(4, 8, 4);
        this.clearVolume(level, origin);

        BlockPos markerA = origin.offset(1, 1, 1);
        BlockPos markerB = origin.offset(2, 1, 1);
        BlockPos support = origin.offset(3, 1, 1);
        BlockPos flower = support.above();

        level.setBlock(markerA, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(markerB, Blocks.BARREL.defaultBlockState(), 3);
        level.setBlock(support, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        level.setBlock(flower, Blocks.DANDELION.defaultBlockState(), 3);

        checks.check(level.getBlockState(markerA).is(Blocks.STONE), "baseline placed a solid block");
        checks.check(level.getBlockEntity(markerB) instanceof BarrelBlockEntity, "baseline created a block entity");

        boolean destroyed = player.gameMode.destroyBlock(support);
        checks.check(destroyed, "baseline player destroyBlock returned true");
        checks.check(level.getBlockState(support).isAir(), "baseline support block became air");
        checks.check(level.getBlockState(flower).isAir(), "baseline adjacent flower became air");

        Pig pig = EntityType.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        checks.check(pig != null, "baseline created an interaction entity");
        if (pig != null) {
            pig.snapTo(markerA.getX() + 0.5D, markerA.getY() + 1.0D, markerA.getZ() + 0.5D, 0.0F, 0.0F);
            level.addFreshEntity(pig);
            checks.check(!pig.isRemoved(), "baseline spawned an entity");
            pig.discard();
            checks.check(pig.isRemoved(), "baseline removed an entity");
        }

        this.clearVolume(level, origin);
    }

    private ServerPlayer firstPlayer(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            throw new IllegalStateException("No baseline test player is available");
        }
        return players.get(0);
    }

    private void clearVolume(ServerLevel level, BlockPos origin) {
        for (BlockPos pos : BlockPos.betweenClosed(origin, origin.offset(5, 4, 5))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private void report(BaselineChecks checks) {
        String result = checks.failedCount() == 0 ? "passed" : "completed with failures";
        System.out.println("Lumi baseline gameplay testing " + result + ": "
                + checks.passedCount() + " passed, "
                + checks.failedCount() + " failed");
        for (String failure : checks.failures()) {
            System.out.println("Lumi baseline gameplay failure: " + failure);
        }
    }

    private static final class BaselineChecks {

        private final List<String> failures = new ArrayList<>();
        private int passedCount;

        private void check(boolean condition, String label) {
            if (condition) {
                this.passedCount++;
            } else {
                this.failures.add(label);
            }
        }

        private int passedCount() {
            return this.passedCount;
        }

        private int failedCount() {
            return this.failures.size();
        }

        private List<String> failures() {
            return List.copyOf(this.failures);
        }
    }
}
