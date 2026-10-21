package io.github.lumi.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import com.moulberry.axiom.editor.EditorUI;
import com.moulberry.axiom.ContextMenuManager;
import com.moulberry.axiom.screen.SwitchBuilderToolScreen;
import com.moulberry.axiom.screen.SwitchHotbarScreen;
import io.github.lumi.client.LumiClient;
import io.github.lumi.client.state.ClientAccentRepository;
import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.client.LumiCompareRenderClientGameTest;
import io.github.lumi.client.ui.LumiHotkeyScreen;
import io.github.lumi.client.ui.LumiUiScale;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Exercises native mouse routing with Axiom's actual editor viewport enabled. */
public final class LumiAxiomEditorClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        if (!LumiClientTestSuite.includes(LumiClientTestSuite.AXIOM_EDITOR)
                || !FabricLoader.getInstance().isModLoaded("axiom")) return;
        try (var ignored = LumiUiScaleTestScope.readableViewport()) {
            LumiClientBehaviorWorld.run(context, "axiom-editor", (client, world, report) -> {
                new LumiUiTestDriver(client).completeOnboardingIfShown();
                run(client, world, report);
            });
        }
    }

    void run(ClientGameTestContext context, TestSingleplayerContext world, LumiBehaviorReport report) {
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        int[] previousSize = context.computeOnClient(client -> new int[] {
                client.getWindow().getWidth(), client.getWindow().getHeight() });
        boolean previousEditor = context.computeOnClient(client -> EditorUI.isEnabled());
        try {
            verifySelectionMenus(context);
            verifyArcade(context, report);
            verifyLiveHud(context, world, report);
            for (boolean editor : new boolean[] {false, true}) {
                context.runOnClient(client -> { if (editor) EditorUI.enable(); else EditorUI.disable(); });
                context.waitFor(client -> EditorUI.isActive() == editor, 1200);
                for (int[] size : new int[][] {{853, 481}, {1280, 720}}) {
                    context.getInput().resizeWindow(size[0], size[1]);
                    int[] previous = null;
                    for (int scale : new int[] {1, 2, 4}) {
                        context.runOnClient(client -> {
                            client.options.guiScale().set(scale);
                            client.resizeDisplay();
                            ContextMenuManager.getInstance().close();
                        });
                        context.setScreen(() -> new LumiHotkeyScreen(null, List.of()));
                        context.runOnClient(client -> {
                            Screen modal = client.screen;
                            var menus = ContextMenuManager.getInstance();
                            menus.open(new Screen(Component.literal("Axiom context-menu close probe")) {
                                @Override public void onClose() { }
                            });
                            menus.close();
                            if (client.screen != modal || menus.isActive()) {
                                throw new AssertionError("Native Axiom context-menu cleanup closed the Lumi screen");
                            }
                        });
                        long opened = System.nanoTime();
                        context.waitFor(client -> System.nanoTime() - opened >= 500_000_000L);
                        int[] layout = context.computeOnClient(client -> {
                            if (!(client.screen instanceof LumiHotkeyScreen) || EditorUI.isActive() != editor) {
                                throw new AssertionError("Lumi screen or actual Axiom editor state changed");
                            }
                            var screen = client.screen;
                            Button close = closeButton(screen);
                            int x = close.getX() + close.getWidth() / 2;
                            int y = close.getY() + close.getHeight() / 2;
                            if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) {
                                throw new AssertionError("Lumi close control lies outside its viewport");
                            }
                            return new int[] {screen.width, screen.height, x, y};
                        });
                        if (previous != null && !Arrays.equals(previous, layout)) {
                            throw new AssertionError("Lumi controls moved with GUI scale while Axiom editor=" + editor);
                        }
                        previous = layout;
                        String name = "axiom-editor-" + editor + "-" + size[0] + "x" + size[1] + "-gui" + scale;
                        context.takeScreenshot(name);
                        double[] cursor = context.computeOnClient(client -> {
                            var window = client.getWindow();
                            float renderScale = LumiUiScale.current().renderScale(window.getGuiScale());
                            return new double[] {
                                    layout[2] * renderScale * window.getScreenWidth() / window.getGuiScaledWidth(),
                                    layout[3] * renderScale * window.getScreenHeight() / window.getGuiScaledHeight()};
                        });
                        context.getInput().setCursorPos(cursor[0], cursor[1]);
                        context.waitTicks(2);
                        if (!context.computeOnClient(client -> closeButton(client.screen).isHovered())) {
                            throw new AssertionError("Native mouse hover missed " + name);
                        }
                        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
                        context.waitFor(client -> client.screen == null, 200);
                        if (!context.computeOnClient(client -> EditorUI.isActive() == editor)) {
                            throw new AssertionError("Closing Lumi changed the Axiom editor state");
                        }
                        report.event("assertion", name, "succeeded", 0, 0,
                                "native editor=" + editor + ";actual mouse close;stable Lumi layout;GameTest virtual resize");
                    }
                }
            }
            report.event("gate", "axiom_editor_ui", "succeeded", 0, 0,
                    "editor enabled/disabled;two virtual sizes;GUI scales 1/2/4;12 actual mouse clicks;screenshots");
            new LumiCompareRenderClientGameTest().verifyDepth(context, world);
            if (!context.computeOnClient(client -> EditorUI.isActive())) {
                throw new AssertionError("Axiom editor stopped during Compare depth checks");
            }
            report.event("gate", "axiom_editor_depth", "succeeded", 0, 0,
                    "actual editor;controlled opaque wall;normal occlusion;visible X-ray");
        } finally {
            context.setScreen(() -> null);
            context.runOnClient(client -> {
                if (previousEditor) EditorUI.enable(); else EditorUI.disable();
                client.options.guiScale().set(previousScale);
            });
            context.getInput().resizeWindow(previousSize[0], previousSize[1]);
        }
    }

    private static void verifyArcade(ClientGameTestContext context, LumiBehaviorReport report) {
        context.runOnClient(client -> { EditorUI.disable(); ContextMenuManager.getInstance().close(); });
        context.setScreen(() -> new LumiHotkeyScreen(null, List.of()));
        context.waitTicks(6);
        context.takeScreenshot("lumi-hotkey-secret-code");
        context.getInput().pressKey(InputConstants.KEY_UP);
        context.getInput().pressKey(InputConstants.KEY_UP);
        context.takeScreenshot("lumi-secret-code-progress");
        context.getInput().pressKey(InputConstants.KEY_LEFT);
        context.takeScreenshot("lumi-secret-code-reset");
        for (int key : new int[]{InputConstants.KEY_UP, InputConstants.KEY_UP,
                InputConstants.KEY_DOWN, InputConstants.KEY_DOWN, InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT,
                InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_B, InputConstants.KEY_A}) {
            context.getInput().pressKey(key);
        }
        context.waitFor(client -> client.screen != null
                && client.screen.getClass().getSimpleName().equals("LumiAstroInvadersScreen"), 100);
        context.getInput().holdKeyFor(InputConstants.KEY_SPACE, 25);
        context.getInput().holdKeyFor(InputConstants.KEY_LEFT, 5);
        context.takeScreenshot("lumi-alien-invaders-playing");
        context.getInput().pressKey(InputConstants.KEY_P);
        context.waitTicks(3);
        context.takeScreenshot("lumi-astro-invaders");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitForScreen(LumiHotkeyScreen.class);
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitForScreen(null);
        report.event("gate", "astro_invaders", "succeeded", 0, 0,
                "incremental Konami input and reset;alien arcade shoot/move/pause;Escape;screenshots");
    }

    private static Button closeButton(Screen screen) {
        String label = Component.translatable("luma.action.close").getString();
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.active && button.visible && button.getMessage().getString().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("Missing visible close control"));
    }

    private static void verifyLiveHud(ClientGameTestContext context, TestSingleplayerContext world,
            LumiBehaviorReport report) {
        var preferences = new ClientAccentRepository(
                FabricLoader.getInstance().getConfigDir().resolve("lumi-accent"));
        int previous = preferences.load();
        try {
            context.runOnClient(client -> {
                EditorUI.disable();
                for (String command : List.of("lumi accent default", "lumi accent #8ECFFF", "lumi motion true")) {
                    var parsed = client.getConnection().getCommands().parse(
                            command, client.getConnection().getSuggestionsProvider());
                    if (parsed.getReader().canRead() || parsed.getContext().getCommand() == null)
                        throw new AssertionError("Client chat rejected local command: " + command);
                }
                client.getConnection().sendCommand("lumi accent #8ECFFF");
            });
            if (preferences.load() != 0xff8ecfff) throw new AssertionError("Accent command did not persist HEX color");
            context.waitFor(client -> LumiClient.history().state().snapshot().isPresent(), 1200);
            var actions = new LumiBehaviorActions(world.getServer(), report);
            var position = world.getServer().computeOnServer(server ->
                    server.getPlayerList().getPlayers().getFirst().blockPosition().east(2));
            var block = actions.surfacePosition(position.getX(), position.getZ());
            var before = world.getServer().computeOnServer(server -> server.overworld().getBlockState(block));
            actions.placeBlocks("hud_fixture", Items.STONE, List.of(block));
            try {
                var operations = new LumiBehaviorOperations(context, world.getServer(), report);
                operations.undoVisible("hud_single_block", false, block, before);
                operations.redo("hud_single_block");
                if (!world.getServer().computeOnServer(server -> server.overworld()
                        .getBlockState(block).is(Blocks.STONE)))
                    throw new AssertionError("Single-block Redo did not restore stone");
            } catch (IOException failed) {
                throw new AssertionError("Single-block Undo/Redo failed", failed);
            }
            var save = context.computeOnClient(client -> LumiClient.networking().save("HUD courtyard"));
            waitForSuccess(context, save);
            context.waitTicks(10);
            context.takeScreenshot("lumi-hud-real-save-blue");
            context.runOnClient(client -> client.getConnection().sendCommand("lumi accent default"));
            if (preferences.load() != ClientAccentRepository.DEFAULT)
                throw new AssertionError("Accent command did not restore default color");
            String originalBranch = context.computeOnClient(client ->
                    LumiClient.history().state().snapshot().orElseThrow().branchName());
            var branch = context.computeOnClient(client -> LumiClient.networking().createBranch("hud-study"));
            waitForSuccess(context, branch);
            context.waitFor(client -> !LumiClient.history().state().snapshot().orElseThrow()
                    .branchName().equals(originalBranch), 1200);
            var switched = context.computeOnClient(client -> LumiClient.networking().switchBranch(originalBranch));
            waitForSuccess(context, switched);
            context.waitTicks(10);
            context.takeScreenshot("lumi-hud-real-branch-gold");
            context.runOnClient(client -> client.getConnection().sendCommand("lumi branch create hud-forwarded"));
            context.waitTicks(20);
            context.runOnClient(client -> LumiClient.networking().refreshSnapshot());
            context.waitFor(client -> LumiClient.history().state().snapshot().orElseThrow().branches().stream()
                    .anyMatch(value -> value.name().endsWith("/hud-forwarded")), 1200);
            report.event("gate", "production_hud", "succeeded", 0, 0,
                    "real Save and branch switch;HEX/default commands;server-command forwarding;production screenshots");
        } finally {
            context.runOnClient(client -> client.getConnection().sendCommand(
                    "lumi accent " + ClientAccentRepository.hex(previous)));
        }
    }

    private static void waitForSuccess(ClientGameTestContext context, java.util.UUID request) {
        context.waitFor(client -> {
            var event = LumiClient.history().state().events().get(request);
            if (event == null || event.state() == OperationEventPayload.State.ACCEPTED
                    || event.state() == OperationEventPayload.State.PROGRESS) return false;
            if (event.state() != OperationEventPayload.State.SUCCEEDED)
                throw new AssertionError("HUD operation failed: " + event.message());
            return true;
        }, 1200);
    }

    private static void verifySelectionMenus(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var menus = ContextMenuManager.getInstance();
            var main = client.player.getMainHandItem().copy();
            var offhand = client.player.getOffhandItem().copy();
            Screen previousScreen = client.screen;
            try {
                menus.close();
                client.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                client.player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                menus.open(new SwitchHotbarScreen());
                if (!menus.isActive()) throw new AssertionError("Axiom Alt menu was blocked without the selection tool");
                menus.close();
                for (var hand : InteractionHand.values()) {
                    client.player.setItemInHand(hand, new ItemStack(Items.WOODEN_SWORD));
                    menus.open(new SwitchHotbarScreen());
                    if (menus.isActive()) throw new AssertionError("Axiom hotbar menu opened with a wooden sword in " + hand);
                    menus.open(new SwitchBuilderToolScreen());
                    if (menus.isActive()) throw new AssertionError("Axiom tool menu opened with a wooden sword in " + hand);
                    menus.open(new Screen(Component.literal("Unrelated Axiom menu")) {
                        @Override public void onClose() { }
                    });
                    if (!menus.isActive()) throw new AssertionError("Selection tool blocked an unrelated Axiom menu");
                    menus.close();
                    client.player.setItemInHand(hand, ItemStack.EMPTY);
                }
            } finally {
                menus.close();
                client.player.setItemInHand(InteractionHand.MAIN_HAND, main);
                client.player.setItemInHand(InteractionHand.OFF_HAND, offhand);
                client.setScreen(previousScreen);
            }
        });
    }
}
