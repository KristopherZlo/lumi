package io.github.lumi.vanillatest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;

/** Fabric GameTest window/GUI resize control with no Lumi rendering or builder tools. */
public final class LumiVanillaViewportGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("lumi.gametest.vanillaViewport")) return;
        System.out.println("Vanilla viewport runtime: " + Runtime.version() + ";java=" + System.getProperty("java.home"));
        if (FabricLoader.getInstance().isModLoaded("lumi")) throw new AssertionError("Lumi is installed");
        try {
            Class.forName("io.github.lumi.LumiMod", false, getClass().getClassLoader());
            throw new AssertionError("Lumi production classes are on the control classpath");
        } catch (ClassNotFoundException expected) { }
        int[] previous = context.computeOnClient(client -> new int[] {
                client.options.guiScale().get(), client.options.framerateLimit().get(),
                client.getWindow().getWidth(), client.getWindow().getHeight()});
        try {
            context.runOnClient(client -> client.options.framerateLimit().set(30));
            for (int[] size : new int[][] {{1280, 720}, {1920, 1080}, {853, 481}}) {
                context.getInput().resizeWindow(size[0], size[1]);
                for (int scale : new int[] {1, 2, 3, 4, 0}) {
                    context.setScreen(TitleScreen::new);
                    context.runOnClient(client -> {
                        client.options.guiScale().set(scale);
                        client.resizeDisplay();
                    });
                    context.waitTicks(8);
                    context.takeScreenshot("vanilla-scale-" + size[0] + "x" + size[1] + "-gui-" + scale);
                }
            }
        } finally {
            context.runOnClient(client -> {
                client.options.guiScale().set(previous[0]);
                client.options.framerateLimit().set(previous[1]);
                client.resizeDisplay();
            });
            context.getInput().resizeWindow(previous[2], previous[3]);
        }
        try {
            String game = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            Files.writeString(Path.of(System.getProperty("lumi.gametest.completion")),
                    game + "\nvanilla-viewport"
                            + (Boolean.getBoolean("lumi.gametest.fixedNativeWindow") ? "\nfixed-native-window" : ""),
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot persist vanilla viewport completion", failed);
        }
    }
}
