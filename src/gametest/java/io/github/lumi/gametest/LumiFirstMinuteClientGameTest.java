package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.client.ui.LumiDashboardScreen;
import io.github.lumi.client.ui.LumiZonesScreen;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.mixin.ServerLevelEntityManagerAccessor;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Fast real client-to-Netty-to-server smoke for the first minute of play. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiFirstMinuteClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 200;
    private SmokeState state;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!LumiClientTestSuite.includes(LumiClientTestSuite.SMOKE)
                || LumiHistoryBenchmarkConfig.enabled()) return;
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
        operations.save("first-minute");
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

        LightState light = assertClientLightRestore(
                context, server, report, operations, actions,
                probe.offset(8, 32, 0));

        int boundaryX = ((probe.getX() >> 4) + 1) << 4;
        Vec3 entityOrigin = new Vec3(
                boundaryX - 0.5, probe.getY(), probe.getZ() + 0.5);
        Vec3 entityMoved = new Vec3(
                boundaryX + 0.5, probe.getY(), probe.getZ() + 0.5);
        UUID movedEntity = spawnDurableEntity(server, entityOrigin);
        operations.awaitDurability("first_minute_entity_origin");
        CommitId entityOriginSave =
                operations.save("first-minute-entity-origin");
        moveDurableEntity(server, movedEntity, entityMoved);
        operations.awaitDurability("first_minute_entity_moved");
        operations.save("first-minute-entity-moved");
        operations.restore("first-minute-entity-origin", entityOriginSave);
        operations.awaitDurability("first_minute_entity_restored");
        waitFor(context, () -> entityChunksReady(server, entityOrigin),
                "Cross-chunk entity storage did not become ready after Restore");
        assertExactEntity(server, movedEntity, entityOrigin);
        requireConnected(context, server);
        report.assertNoRuntimeFailures();
        report.event("gate", "first_minute", "succeeded", 0, 0,
                "real packets, entity move, Save, Restore, Undo, Redo");
        state = new SmokeState(
                entityOriginSave, probe, blockEntityProbe, pig,
                movedEntity, entityOrigin, light);
    }

    private static LightState assertClientLightRestore(
            ClientGameTestContext context,
            TestServerContext server,
            LumiBehaviorReport report,
            LumiBehaviorOperations operations,
            LumiBehaviorActions actions,
            BlockPos source) throws IOException {
        BlockPos lightProbe = source.east();
        actions.playerCommand("client_light_source",
                "setblock " + coordinates(source) + " minecraft:glowstone");
        actions.playerCommand("client_light_probe",
                "setblock " + coordinates(lightProbe) + " minecraft:air");
        awaitBlock(context, server, source, Blocks.GLOWSTONE);
        int bright = awaitLight(context, server, lightProbe, null);
        operations.awaitDurability("client_light_bright");
        CommitId brightCommit = operations.save("client-light-bright");

        actions.playerCommand("client_light_removed",
                "setblock " + coordinates(source) + " minecraft:air");
        awaitBlock(context, server, source, Blocks.AIR);
        int dark = awaitLight(context, server, lightProbe, null);
        if (bright <= dark) {
            throw new AssertionError("Client-light fixture did not darken after "
                    + "source removal: bright=" + bright + ", dark=" + dark);
        }
        operations.awaitDurability("client_light_dark");
        CommitId darkCommit = operations.save("client-light-dark");
        operations.restore("client-light-bright", brightCommit);

        awaitBlock(context, server, source, Blocks.GLOWSTONE);
        awaitLight(context, server, lightProbe, bright);
        operations.restore("client-light-dark", darkCommit);
        awaitBlock(context, server, source, Blocks.AIR);
        awaitLight(context, server, lightProbe, dark);
        report.event("gate", "restore_light_client", "succeeded", 0, 0,
                "bright=" + bright + ";dark=" + dark);
        return new LightState(source, lightProbe, dark);
    }

    private static void awaitBlock(
            ClientGameTestContext context,
            TestServerContext server,
            BlockPos position,
            Block expected) {
        waitFor(context, () -> {
            var serverState = server.computeOnServer(minecraft -> minecraft
                    .getPlayerList().getPlayers().getFirst().level()
                    .getBlockState(position));
            var clientState = context.computeOnClient(client ->
                    client.level == null ? Blocks.AIR.defaultBlockState()
                            : client.level.getBlockState(position));
            return serverState.equals(clientState) && serverState.is(expected);
        }, "Client did not receive block " + expected);
    }

    private static int awaitLight(
            ClientGameTestContext context,
            TestServerContext server,
            BlockPos position,
            Integer expected) {
        context.waitTicks(5);
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            int serverLight = server.computeOnServer(minecraft -> minecraft
                    .getPlayerList().getPlayers().getFirst().level()
                    .getBrightness(LightLayer.BLOCK, position));
            int clientLight = context.computeOnClient(client ->
                    client.level == null ? -1
                            : client.level.getBrightness(LightLayer.BLOCK, position));
            if (serverLight == clientLight
                    && (expected == null || serverLight == expected)) {
                return serverLight;
            }
            context.waitTick();
        }
        throw new AssertionError("Server and client light did not converge at "
                + position + (expected == null ? "" : " to " + expected));
    }

    private static String coordinates(BlockPos position) {
        return position.getX() + " " + position.getY() + " " + position.getZ();
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
                            state.light().source()).is(Blocks.AIR)
                    && player.level().getBlockState(
                            state.blockEntityProbe()).is(Blocks.CHEST)
                    && player.level().getBlockEntity(
                            state.blockEntityProbe()) != null
                    && !entityPresent(player.level().getEntityInAnyDimension(state.pig()));
        });
        if (!persisted) {
            throw new AssertionError("First-minute state did not survive world reopen");
        }
        awaitBlock(context, server, state.light().source(), Blocks.AIR);
        awaitLight(context, server, state.light().probe(), state.light().level());
        waitFor(context, () -> entityChunksReady(server, state.entityOrigin()),
                "Cross-chunk entity storage did not become ready after reopen");
        assertExactEntity(server, state.movedEntity(), state.entityOrigin());
        report.event("gate", "first_minute_reopen", "succeeded", 0, 0, "");
    }

    private static UUID spawnDurableEntity(
            TestServerContext server, Vec3 position) {
        return server.computeOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            var runtime = LumiMod.serverRuntime().find(player.level()).orElseThrow();
            try (var ignored = DirectLiveActionContext.open(
                    runtime.liveActions(), player.getUUID())) {
                Entity entity = Objects.requireNonNull(EntityType.ARMOR_STAND.create(
                        player.level(), EntitySpawnReason.COMMAND));
                entity.setPos(position);
                entity.setNoGravity(true);
                if (!player.level().addFreshEntity(entity)) {
                    throw new AssertionError(
                            "Could not spawn the cross-chunk entity");
                }
                player.teleportTo(position.x, position.y, position.z);
                return entity.getUUID();
            }
        });
    }

    private static void moveDurableEntity(
            TestServerContext server, UUID id, Vec3 position) {
        server.runOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            var runtime = LumiMod.serverRuntime().find(player.level()).orElseThrow();
            Entity entity = Objects.requireNonNull(
                    player.level().getEntityInAnyDimension(id));
            try (var ignored = DirectLiveActionContext.open(
                    runtime.liveActions(), player.getUUID())) {
                var pending = runtime.liveEntities().begin(entity).orElseThrow(
                        () -> new AssertionError(
                                "Could not capture the cross-chunk entity move"));
                entity.setPos(position);
                if (!runtime.liveEntities().finish(pending)) {
                    throw new AssertionError(
                            "Cross-chunk entity move was not captured");
                }
            } catch (IOException failed) {
                throw new AssertionError(
                        "Could not record the cross-chunk entity move", failed);
            }
        });
    }

    private static void assertExactEntity(
            TestServerContext server, UUID id, Vec3 expectedPosition) {
        server.runOnServer(minecraft -> {
            var level = minecraft.getPlayerList().getPlayers().getFirst().level();
            ChunkPos origin = new ChunkPos(BlockPos.containing(expectedPosition));
            if (level.getChunkSource().getChunkNow(origin.x, origin.z) == null
                    || level.getChunkSource().getChunkNow(origin.x + 1, origin.z) == null) {
                throw new AssertionError(
                        "Cross-chunk entity audit needs both chunks loaded");
            }
            Entity found = null;
            int count = 0;
            for (Entity entity : level.getAllEntities()) {
                if (entity.getUUID().equals(id)) {
                    found = entity;
                    count++;
                }
            }
            if (count != 1) {
                throw new AssertionError(
                        "Expected one durable entity " + id + ", found " + count);
            }
            if (!found.position().equals(expectedPosition)) {
                throw new AssertionError("Durable entity " + id + " was at "
                        + found.position() + " instead of " + expectedPosition);
            }
        });
    }

    private static boolean entityChunksReady(
            TestServerContext server, Vec3 expectedPosition) {
        return server.computeOnServer(minecraft -> {
            var level = minecraft.getPlayerList().getPlayers().getFirst().level();
            ChunkPos origin = new ChunkPos(BlockPos.containing(expectedPosition));
            var entities = ((ServerLevelEntityManagerAccessor) level)
                    .lumi$entityManager();
            entities.processPendingLoads();
            return level.getChunkSource().getChunkNow(origin.x, origin.z) != null
                    && level.getChunkSource().getChunkNow(
                            origin.x + 1, origin.z) != null
                    && entities.areEntitiesLoaded(origin.toLong())
                    && entities.areEntitiesLoaded(
                            ChunkPos.asLong(origin.x + 1, origin.z));
        });
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
            UUID pig,
            UUID movedEntity,
            Vec3 entityOrigin,
            LightState light) { }

    private record LightState(BlockPos source, BlockPos probe, int level) { }
}
