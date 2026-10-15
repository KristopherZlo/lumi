package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Single mouse presses with authoritative results; never retries a refused action. */
final class LumiInteractionTestDriver {
    private final ClientGameTestContext context;
    private final TestServerContext server;
    private final LumiBehaviorReport report;

    LumiInteractionTestDriver(ClientGameTestContext context, TestServerContext server,
            LumiBehaviorReport report) {
        this.context = context;
        this.server = server;
        this.report = report;
    }

    void exercise(String phase, BlockPos chest, BlockPos block, BlockPos lever) {
        var samples = new ArrayList<Sample>();
        for (int repetition = 0; repetition < 6; repetition++) {
            aim(chest, Items.AIR);
            samples.add(click(phase, "container", repetition, chest, GLFW.GLFW_MOUSE_BUTTON_RIGHT,
                    player -> player.containerMenu != player.inventoryMenu,
                    client -> client.screen instanceof AbstractContainerScreen<?>));
            server.runOnServer(minecraft -> {
                var opened = (ChestBlockEntity) player(minecraft).level().getBlockEntity(chest);
                if (opened.getLootTable() != null || opened.isEmpty()) {
                    throw new AssertionError("Chest did not generate its seeded loot on first open");
                }
            });
            context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);
            context.waitForScreen(null);
            awaitClient(client -> client.player.containerMenu == client.player.inventoryMenu);
            awaitServer(player -> player.containerMenu == player.inventoryMenu);

            aim(block.below(), Items.STONE);
            samples.add(click(phase, "place", repetition, block, GLFW.GLFW_MOUSE_BUTTON_RIGHT,
                    player -> player.level().getBlockState(block).is(Blocks.STONE),
                    client -> client.level.getBlockState(block).is(Blocks.STONE)));
            aim(block, Items.AIR);
            samples.add(click(phase, "break", repetition, block, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                    player -> player.level().getBlockState(block).isAir(),
                    client -> client.level.getBlockState(block).isAir()));

            aim(lever, Items.AIR);
            boolean powered = server.computeOnServer(minecraft -> player(minecraft)
                    .level().getBlockState(lever).getValue(BlockStateProperties.POWERED));
            samples.add(click(phase, "lever", repetition, lever, GLFW.GLFW_MOUSE_BUTTON_RIGHT,
                    player -> player.level().getBlockState(lever)
                            .getValue(BlockStateProperties.POWERED) != powered,
                    client -> client.level.getBlockState(lever)
                            .getValue(BlockStateProperties.POWERED) != powered));
        }
        for (String action : List.of("container", "break", "place", "lever")) {
            var repeated = samples.stream().filter(sample -> sample.action.equals(action))
                    .skip(1).mapToLong(Sample::clientNanos).sorted().toArray();
            report.event("interaction_summary", phase + "_" + action, "measured", 0, 0,
                    "firstMs=" + millis(samples.stream().filter(sample -> sample.action.equals(action))
                            .findFirst().orElseThrow().clientNanos)
                            + ";repeatP50Ms=" + millis(repeated[repeated.length / 2])
                            + ";repeatMaxMs=" + millis(repeated[repeated.length - 1]));
        }
    }

    private Sample click(String phase, String action, int repetition, BlockPos target, int button,
            Predicate<ServerPlayer> serverResult, Predicate<Minecraft> clientResult) {
        boolean originNeeded = server.computeOnServer(minecraft -> {
            var player = player(minecraft);
            var runtime = LumiMod.serverRuntime().find(player.level()).orElseThrow();
            if (runtime.freeze().isFrozen()) {
                throw new AssertionError("Interaction started during a world operation");
            }
            return runtime.mutations().needsOrigin(
                    io.github.lumi.minecraft.world.MinecraftSectionCapture.key(target));
        });
        if (repetition == 0 && action.equals("place")
                && (phase.equals("created") || phase.equals("new_section")) && !originNeeded) {
            throw new AssertionError("First-touch placement did not require an origin capture");
        }
        try (var timing = LumiInteractionTiming.open()) {
            report.event("interaction_input", phase + "_" + action + "_" + repetition,
                    "ready", 0, 0, clientInputState() + server.computeOnServer(minecraft ->
                            ";clientLoaded=" + player(minecraft).connection.hasClientLoaded()));
            long started = System.nanoTime();
            context.getInput().holdMouseFor(button, 1);
            int serverTicks;
            try {
                serverTicks = awaitServer(serverResult);
            } catch (AssertionError failed) {
                String state = clientInputState();
                state += server.computeOnServer(minecraft -> ";serverPlayer=" + player(minecraft).position()
                        + ";clientLoaded=" + player(minecraft).connection.hasClientLoaded()
                        + ";inRange=" + player(minecraft).isWithinBlockInteractionRange(target, 1.0)
                        + ";serverBlock=" + player(minecraft).level().getBlockState(target));
                report.event("interaction", phase + "_" + action + "_" + repetition,
                        "failed", 200, (System.nanoTime() - started) / 1_000_000,
                        "presses=1;clientAttempts=" + timing.clientAttempts()
                                + ";clientUses=" + timing.clientUses() + ";clientResult=" + timing.clientResult()
                                + ";serverPackets=" + timing.serverPackets()
                                + ";handlerCalls=" + timing.calls() + ";" + state);
                throw new AssertionError(phase + " " + action + " did not apply; " + state, failed);
            }
            long serverNanos = System.nanoTime() - started;
            int clientTicks = awaitClient(clientResult);
            long clientNanos = System.nanoTime() - started;
            if (timing.calls() != 1) {
                throw new AssertionError("One " + action + " click invoked " + timing.calls()
                        + " server handlers; phase=" + phase);
            }
            report.event("interaction", phase + "_" + action + "_" + repetition,
                    "succeeded", serverTicks + clientTicks, clientNanos / 1_000_000,
                    "presses=1;clientAttempts=" + timing.clientAttempts()
                            + ";first=" + (repetition == 0) + ";originNeeded=" + originNeeded
                            + ";serverObservedMs=" + millis(serverNanos)
                            + ";clientObservedMs=" + millis(clientNanos)
                            + ";handlerStartMs=" + millis(timing.handlerStarted() - started)
                            + ";handlerCompletedMs=" + millis(timing.handlerFinished() - started)
                            + ";handlerMs=" + millis(timing.handlerNanos()));
            return new Sample(action, clientNanos);
        }
    }

    private String clientInputState() {
        return context.computeOnClient(client -> "screen=" + client.screen
                + ";mouseGrabbed=" + client.mouseHandler.isMouseGrabbed()
                + ";windowActive=" + client.isWindowActive()
                + ";useBound=" + client.options.keyUse.saveString()
                + ";hit=" + client.hitResult.getType()
                + ";target=" + (client.hitResult instanceof BlockHitResult hit ? hit.getBlockPos() : "entity")
                + ";handsBusy=" + client.player.isHandsBusy()
                + ";usingItem=" + client.player.isUsingItem()
                + ";destroying=" + client.gameMode.isDestroying()
                + ";axiomTinker=" + com.moulberry.axiom.Axiom.configuration.capabilities.tinker
                + ";axiomBulldozer=" + com.moulberry.axiom.Axiom.configuration.capabilities.bulldozer
                + ";axiomEditor=" + com.moulberry.axiom.editor.EditorUI.isActive()
                + ";axiomMenu=" + com.moulberry.axiom.ContextMenuManager.getInstance().isActive());
    }

    private void aim(BlockPos target, Item item) {
        new LumiUiTestDriver(context).completeOnboardingIfShown();
        server.runOnServer(minecraft -> {
            var player = player(minecraft);
            player.teleportTo(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 3.0);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.setItemInHand(InteractionHand.MAIN_HAND,
                    item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item));
            player.inventoryMenu.broadcastChanges();
        });
        awaitClient(client -> client.player != null && client.level != null
                && client.player.distanceToSqr(target.getX() + 0.5,
                        target.getY() + 1.0, target.getZ() + 3.0) < 0.25
                && (item == Items.AIR ? client.player.getMainHandItem().isEmpty()
                        : client.player.getMainHandItem().is(item)));
        context.runOnClient(client -> client.player.lookAt(
                EntityAnchorArgument.Anchor.EYES,
                Vec3.atCenterOf(target).add(0, item == Items.AIR ? -0.25 : 0.49, 0)));
        // Vanilla right-click cooldown must expire before a new independent press.
        context.waitTicks(6);
        for (int tick = 0; tick < 200; tick++) {
            // Initial history may deliver onboarding after the teleport packet.
            new LumiUiTestDriver(context).completeOnboardingIfShown();
            context.runOnClient(client -> {
                // A background GameTest window otherwise consumes its first click to grab the mouse.
                if (client.screen == null) {
                    //? if >=26.1 {
                    /*((io.github.lumi.gametest.mixin.WindowFocusAccessor) (Object) client.getWindow()).lumi$setFocused(true);
                    *///?} else {
                    client.setWindowActive(true);
                    //?}
                    client.mouseHandler.grabMouse();
                }
            });
            if (context.computeOnClient(client -> client.screen == null
                    && client.mouseHandler.isMouseGrabbed()
                    && client.hitResult instanceof BlockHitResult hit
                    && hit.getBlockPos().equals(target)
                    && (item == Items.AIR || hit.getDirection() == Direction.UP))) return;
            context.waitTick();
        }
        throw new AssertionError("Camera did not target " + target);
    }

    private int awaitServer(Predicate<ServerPlayer> predicate) {
        for (int tick = 0; tick < 200; tick++) {
            if (server.computeOnServer(minecraft -> predicate.test(player(minecraft)))) return tick;
            context.waitTick();
        }
        throw new AssertionError("Single interaction did not reach the server in 200 ticks");
    }

    private int awaitClient(Predicate<Minecraft> predicate) {
        try {
            return context.waitFor(predicate, 200);
        } catch (AssertionError failed) {
            String state = context.computeOnClient(client -> "screen=" + client.screen
                    + ";hit=" + client.hitResult + ";player=" + client.player.position()
                    + ";hand=" + client.player.getMainHandItem()
                    + ";yaw=" + client.player.getYRot() + ";pitch=" + client.player.getXRot());
            throw new AssertionError("Client interaction condition timed out; " + state, failed);
        }
    }

    private static ServerPlayer player(net.minecraft.server.MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private record Sample(String action, long clientNanos) { }
}
