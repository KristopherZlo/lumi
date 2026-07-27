package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.client.ui.LumiDashboardScreen;
import io.github.lumi.client.ui.LumiZonesScreen;
import io.github.lumi.domain.model.CommitId;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Fast real client-to-Netty-to-server smoke for the first minute of play. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiFirstMinuteClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 200;
    private SmokeState state;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var ignored = LumiUiScaleTestScope.readableViewport()) {
            LumiClientBehaviorWorld.runWithReopen(
                    context, "first-minute", this::exercise, this::verifyReopened);
        }
    }

    private void exercise(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            LumiBehaviorReport report) throws IOException {
        TestServerContext server = singleplayer.getServer();
        LumiUiTestDriver ui = new LumiUiTestDriver(context);
        LumiBehaviorOperations operations =
                new LumiBehaviorOperations(context, server, report);
        LumiBehaviorActions actions = new LumiBehaviorActions(server, report);
        ui.completeOnboardingIfShown();
        ui.awaitHistory();

        BlockPos probe = actions.surfacePosition(2, 2);
        BlockPos blockEntityProbe = probe.offset(0, 0, 1);
        server.runOnServer(minecraft -> {
            var level = minecraft.getPlayerList().getPlayers().getFirst().level();
            level.setBlockAndUpdate(
                    blockEntityProbe, Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(probe, Blocks.STONE.defaultBlockState());
        });
        waitFor(context, () -> context.computeOnClient(client ->
                        client.level != null
                                && client.level.getBlockState(probe).is(Blocks.STONE)),
                "Client did not receive the smoke probe");
        new LumiPlayerPacketTestDriver(
                context, server, report, TIMEOUT_TICKS)
                .assertBreakAndPlace("first_minute_block_packets", probe);

        ui.openTab("luma.tab.zones", LumiZonesScreen.class);
        ui.pressUniqueButton(LumiZonesScreen.class, "luma.zones.render_focused");
        context.waitTicks(2);
        ui.pressUniqueButton(LumiZonesScreen.class, "luma.zones.render_all");
        ui.pressUniqueButton(LumiZonesScreen.class, "luma.zones.render_hidden");
        ui.closeScreen(LumiZonesScreen.class, LumiDashboardScreen.class);
        ui.closeScreen(LumiDashboardScreen.class, null);

        UUID pig = spawnFragilePig(server, probe.offset(4, 0, 0));
        waitFor(context, () -> context.computeOnClient(client ->
                        connected(client)
                                && client.player.getMainHandItem().is(Items.DIAMOND_SWORD)
                                && clientEntity(client, pig) != null),
                "Client did not synchronize the attack target");
        context.runOnClient(client -> client.gameMode.attack(
                client.player,
                Objects.requireNonNull(clientEntity(client, pig))));
        waitFor(context, () -> !entityPresent(server, pig),
                "Real client attack did not remove the pig");
        requireConnected(context, server);

        operations.awaitDurability("first_minute_actions");
        CommitId save = operations.save("first-minute");
        ui.completeOnboardingIfShown();
        operations.undo("first_minute_entity");
        waitFor(context, () -> entityPresent(server, pig),
                "Undo did not restore the attacked pig");
        waitForStableIdle(context, server,
                "Undo did not release the world operation slot");
        operations.redo("first_minute_entity");
        waitFor(context, () -> !entityPresent(server, pig),
                "Redo did not remove the attacked pig");
        waitForStableIdle(context, server,
                "Redo did not release the world operation slot");
        operations.awaitDurability("first_minute_final");
        requireConnected(context, server);
        report.assertNoRuntimeFailures();
        report.event("gate", "first_minute", "succeeded", 0, 0,
                "real block packets, overlay, entity attack, save, undo, redo");
        state = new SmokeState(save, probe, blockEntityProbe, pig);
    }

    private void verifyReopened(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            LumiBehaviorReport report) throws IOException {
        if (state == null) {
            throw new AssertionError("First-minute state was not captured");
        }
        TestServerContext server = singleplayer.getServer();
        LumiUiTestDriver ui = new LumiUiTestDriver(context);
        LumiBehaviorOperations operations =
                new LumiBehaviorOperations(context, server, report);
        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        operations.awaitDurability("first_minute_reopen");
        requireConnected(context, server);
        boolean persisted = server.computeOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            var runtime = LumiMod.serverRuntime().find(player.level()).orElseThrow();
            return runtime.activeRef().commit().equals(state.save())
                    && player.level().getBlockState(state.probe()).is(Blocks.STONE)
                    && player.level().getBlockState(
                            state.blockEntityProbe()).is(Blocks.CHEST)
                    && player.level().getBlockEntity(
                            state.blockEntityProbe()) != null
                    && !entityPresent(player.level().getEntityInAnyDimension(state.pig()));
        });
        if (!persisted) {
            throw new AssertionError("First-minute state did not survive world reopen");
        }
        report.event("gate", "first_minute_reopen", "succeeded", 0, 0, "");
    }

    private static UUID spawnFragilePig(
            TestServerContext server, BlockPos position) {
        return server.computeOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            Pig pig = Objects.requireNonNull(EntityType.PIG.create(
                    player.level(), EntitySpawnReason.COMMAND));
            pig.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
            pig.setHealth(1.0F);
            if (!player.level().addFreshEntity(pig)) {
                throw new AssertionError("Could not spawn the smoke pig");
            }
            player.teleportTo(pig.getX() + 2, pig.getY(), pig.getZ());
            player.setItemInHand(
                    InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
            player.inventoryMenu.broadcastChanges();
            return pig.getUUID();
        });
    }

    private static Entity clientEntity(Minecraft client, UUID id) {
        if (client.level == null) {
            return null;
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.getUUID().equals(id)) {
                return entity;
            }
        }
        return null;
    }

    private static boolean entityPresent(TestServerContext server, UUID id) {
        return server.computeOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            return entityPresent(player.level().getEntityInAnyDimension(id));
        });
    }

    private static boolean entityPresent(Entity entity) {
        return entity != null && !entity.isRemoved();
    }

    private static boolean operationsIdle(TestServerContext server) {
        return server.computeOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            var operations = LumiMod.serverRuntime().find(player.level())
                    .orElseThrow().operations();
            return !operations.hasActiveOperation() && operations.queuedCount() == 0;
        });
    }

    private static void waitForStableIdle(
            ClientGameTestContext context,
            TestServerContext server,
            String failure) {
        int idleTicks = 0;
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (!operationsIdle(server)) {
                idleTicks = 0;
            } else if (++idleTicks == 3) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(failure + " within " + TIMEOUT_TICKS + " ticks");
    }

    private static void requireConnected(
            ClientGameTestContext context, TestServerContext server) {
        if (!context.computeOnClient(LumiFirstMinuteClientGameTest::connected)
                || !server.computeOnServer(minecraft ->
                        !minecraft.getPlayerList().getPlayers().isEmpty())) {
            throw new AssertionError("First-minute smoke lost its player connection");
        }
    }

    private static boolean connected(Minecraft client) {
        return client.player != null && client.level != null
                && client.gameMode != null && client.getConnection() != null
                && client.getConnection().isAcceptingMessages();
    }

    private static void waitFor(
            ClientGameTestContext context,
            BooleanSupplier condition,
            String failure) {
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (condition.getAsBoolean()) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(failure + " within " + TIMEOUT_TICKS + " ticks");
    }

    private record SmokeState(
            CommitId save,
            BlockPos probe,
            BlockPos blockEntityProbe,
            UUID pig) { }
}
