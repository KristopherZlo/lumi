package io.github.lumi.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import com.moulberry.axiom.editor.EditorUI;
import com.moulberry.axiom.ContextMenuManager;
import io.github.lumi.client.LumiCompareRenderClientGameTest;
import io.github.lumi.client.ui.LumiHotkeyScreen;
import io.github.lumi.client.ui.LumiUiScale;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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

    private static Button closeButton(Screen screen) {
        String label = Component.translatable("luma.action.close").getString();
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.active && button.visible && button.getMessage().getString().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("Missing visible close control"));
    }
}
