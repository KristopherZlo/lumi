package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Sends ordinary break/place packets and verifies their authoritative server result. */
final class LumiPlayerPacketTestDriver {
    private final ClientGameTestContext context;
    private final TestServerContext server;
    private final LumiBehaviorReport report;
    private final int timeoutTicks;

    LumiPlayerPacketTestDriver(
            ClientGameTestContext context,
            TestServerContext server,
            LumiBehaviorReport report,
            int timeoutTicks) {
        this.context = context;
        this.server = server;
        this.report = report;
        this.timeoutTicks = timeoutTicks;
    }

    void assertBreakAndPlace(String assertionName, BlockPos probe) {
        long started = System.nanoTime();
        server.runOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            player.teleportTo(
                    probe.getX() + 2.5, probe.getY(), probe.getZ() + 0.5);
        });
        await(client -> client.player != null && client.level != null
                        && client.level.getBlockState(probe).is(Blocks.STONE)
                        && client.player.distanceToSqr(Vec3.atCenterOf(probe)) < 25.0,
                "Client did not synchronize the recovered probe");

        server.runOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.inventoryMenu.broadcastChanges();
        });
        await(client -> client.player != null
                        && client.player.getMainHandItem().isEmpty(),
                "Client did not synchronize the empty break hand");

        boolean breaking = context.computeOnClient(client -> {
            require(client.gameMode != null, "Client game mode is unavailable");
            return client.gameMode.startDestroyBlock(probe, Direction.UP);
        });
        require(breaking, "Client refused to send the break action");
        awaitServerBlock(probe, Blocks.AIR,
                "Player break packet was cancelled during " + assertionName);
        await(client -> client.level != null && client.level.getBlockState(probe).isAir(),
                "Client did not observe the broken recovery probe");

        server.runOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
            player.inventoryMenu.broadcastChanges();
        });
        await(client -> client.player != null
                        && client.player.getMainHandItem().is(Items.STONE),
                "Client did not synchronize the placement item");
        var placement = context.computeOnClient(client -> {
            require(client.player != null && client.gameMode != null,
                    "Client player interaction is unavailable");
            BlockPos support = probe.below();
            BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(support).add(0.0, 0.5, 0.0),
                    Direction.UP, support, false);
            return client.gameMode.useItemOn(
                    client.player, InteractionHand.MAIN_HAND, hit);
        });
        require(placement.consumesAction(),
                "Client refused to send the placement action");
        awaitServerBlock(probe, Blocks.STONE,
                "Player placement packet was cancelled during " + assertionName);
        report.event("assertion", assertionName, "succeeded", 0,
                elapsedMillis(started), probe.toShortString());
    }

    private void awaitServerBlock(BlockPos position, Block expected, String failure) {
        for (int tick = 0; tick < timeoutTicks; tick++) {
            boolean matches = server.computeOnServer(minecraft ->
                    minecraft.getPlayerList().getPlayers().getFirst().level()
                            .getBlockState(position).is(expected));
            if (matches) {
                return;
            }
            context.waitTick();
        }
        String state = server.computeOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            var runtime = LumiMod.serverRuntime().find(player.level()).orElseThrow();
            return "block=" + player.level().getBlockState(position)
                    + ", player=" + player.blockPosition()
                    + ", frozen=" + runtime.freeze().isFrozen()
                    + ", operationActive=" + runtime.operations().hasActiveOperation()
                    + ", recoveryPending=" + runtime.recoveryJournal().isPresent();
        });
        throw new AssertionError(failure + " within " + timeoutTicks
                + " ticks; " + state);
    }

    private void await(Predicate<Minecraft> predicate, String failure) {
        for (int tick = 0; tick < timeoutTicks; tick++) {
            if (context.computeOnClient(predicate::test)) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(failure + " within " + timeoutTicks + " ticks");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
