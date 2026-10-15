package io.github.lumi.gametest;

import io.github.lumi.client.ui.LumiHotkeyScreen;
import io.github.lumi.client.ui.LumiUiScale;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.MenuTooltipPositioner;
import net.minecraft.network.chat.Component;
//? if <26.3
import org.lwjgl.glfw.GLFW;

/** Checks native Lumi geometry, hover and mouse dispatch without loading a world. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiUiScaleClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!LumiClientTestSuite.includes(LumiClientTestSuite.UI_SCALE)
                || LumiHistoryBenchmarkConfig.enabled()) return;
        Screen parent = context.computeOnClient(client -> client.screen);
        int[] previous = context.computeOnClient(client -> new int[] {
                client.options.guiScale().get(), client.options.framerateLimit().get(),
                client.getWindow().getWidth(), client.getWindow().getHeight()});
        String property = LumiUiScale.TARGET_GUI_SCALE_PROPERTY;
        String previousOverride = System.getProperty(property);
        try {
            System.clearProperty(property);
            context.runOnClient(client -> client.options.framerateLimit().set(30));
            for (int[] size : new int[][] {{1280, 720}, {1920, 1080}, {853, 481}}) {
                context.getInput().resizeWindow(size[0], size[1]);
                Geometry expected = null;
                for (int guiScale : new int[] {1, 2, 3, 4, 0}) {
                    context.setScreen(() -> new LumiHotkeyScreen(parent, List.of()));
                    context.runOnClient(client -> {
                        client.options.guiScale().set(guiScale);
                        client.resizeDisplay();
                    });
                    context.waitTicks(8);
                    Geometry actual = context.computeOnClient(client -> Geometry.of(client.screen));
                    if (actual.width() != (size[0] + 1) / 2
                            || actual.height() != (size[1] + 1) / 2) {
                        throw new AssertionError("Lumi viewport changed with Minecraft GUI scale "
                                + guiScale + ": " + actual);
                    }
                    if (expected != null && !expected.equals(actual)) {
                        throw new AssertionError("Lumi controls moved at Minecraft GUI scale " + guiScale);
                    }
                    expected = actual;
                    double[] cursor = context.computeOnClient(client -> {
                        var window = client.getWindow();
                        float renderScale = LumiUiScale.current().renderScale(window.getGuiScale());
                        Button close = closeButton(client.screen);
                        return new double[] {
                                (close.getX() + close.getWidth() / 2.0) * renderScale
                                        * window.getScreenWidth() / window.getGuiScaledWidth(),
                                (close.getY() + close.getHeight() / 2.0) * renderScale
                                        * window.getScreenHeight() / window.getGuiScaledHeight()};
                    });
                    context.getInput().setCursorPos(cursor[0], cursor[1]);
                    context.waitTicks(2);
                    if (!context.computeOnClient(client -> closeButton(client.screen).isHovered())) {
                        throw new AssertionError("Close button hover missed at Minecraft GUI scale " + guiScale);
                    }
                    Path screenshot = context.takeScreenshot(
                            "lumi-scale-" + size[0] + "x" + size[1] + "-gui-" + guiScale);
                    verifyTooltip(context, screenshot);
                    context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    context.waitFor(client -> client.screen == parent);
                }
            }
        } finally {
            if (previousOverride == null) System.clearProperty(property);
            else System.setProperty(property, previousOverride);
            context.setScreen(() -> parent);
            context.runOnClient(client -> {
                client.options.guiScale().set(previous[0]);
                client.options.framerateLimit().set(previous[1]);
                client.resizeDisplay();
            });
            context.getInput().resizeWindow(previous[2], previous[3]);
        }
    }

    private static void verifyTooltip(ClientGameTestContext context, Path screenshot) {
        int[] bounds = context.computeOnClient(client -> {
            Button close = closeButton(client.screen);
            int textWidth = client.font.width(close.getMessage());
            int textHeight = client.font.lineHeight;
            var position = new MenuTooltipPositioner(close.getRectangle()).positionTooltip(
                    client.screen.width, client.screen.height,
                    close.getX() + close.getWidth() / 2, close.getY() + close.getHeight() / 2,
                    textWidth, textHeight);
            return new int[] {position.x() * 2, position.y() * 2, textWidth * 2, textHeight * 2};
        });
        try {
            var image = ImageIO.read(screenshot.toFile());
            int textPixels = 0;
            for (int y = bounds[1]; y < Math.min(image.getHeight(), bounds[1] + bounds[3]); y++) {
                for (int x = bounds[0]; x < Math.min(image.getWidth(), bounds[0] + bounds[2]); x++) {
                    int color = image.getRGB(x, y);
                    if (((color >> 16) & 255) > 200 && ((color >> 8) & 255) > 200
                            && (color & 255) > 200) textPixels++;
                }
            }
            if (textPixels < 20) throw new AssertionError("Tooltip text is missing at scale 2: " + screenshot);
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot read scale test screenshot", failed);
        }
    }

    private static Button closeButton(Screen screen) {
        String label = Component.translatable("luma.action.close").getString();
        return screen.children().stream().filter(Button.class::isInstance)
                .map(Button.class::cast).filter(button -> button.getMessage().getString().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("Lumi close button is missing"));
    }

    private record Geometry(int width, int height, int closeX, int closeY, int closeWidth, int closeHeight) {
        static Geometry of(Screen screen) {
            Button close = closeButton(screen);
            return new Geometry(screen.width, screen.height, close.getX(), close.getY(),
                    close.getWidth(), close.getHeight());
        }
    }
}
